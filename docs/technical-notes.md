# 晕动舒缓显示（欧加通用）· KernelSU + LSPosed 模块 v5.0

## 这个模块做什么

给**缺少「晕动舒缓显示」的 OPPO / 一加 / realme（欧加）ColorOS 16 机型**补全原生功能。

圆点是 **ColorOS 原厂 `AntiDizzyView` 画的**，模块负责三件事：

1. **补上被厂商隐藏的特性开关**（不同机型位置不同 → 自动探测）；
2. **用手机自带传感器替代缺失的车辆传感器**（OPPO 私有 `car motion` / `car scene`），
   把数据按原厂布局喂进 `handleMotionSensorEvent()`，之后全部走原厂算法与绘制；
3. **给原厂圆点补上边缘抗锯齿**（原厂 shader 是纯色硬填充，边缘有明显锯齿）。

---

## 一、自动适配（换机型不用改包）

| 适配项 | 做法 |
|---|---|
| 特性配置文件路径 | 依次探测 7 个候选路径（`/system_ext/etc/permissions/`、`/my_product/etc/{permissions,extension}/` 等），谁存在就补谁；内容由脚本在开机时生成，不依赖机型 |
| 原厂是否已有车辆传感器 | 扫描 `SensorManager` 全表，命中 `33171091/33171092` 或名称含 `car motion`/`car scene` → **不注入**，保持原厂行为 |
| 运动数据源 | 优先 `TYPE_LINEAR_ACCELERATION`（系统已去重力，100Hz），没有则回退加速度计 + 软件去重力（50Hz） |
| 重力方向 | 有 `TYPE_GRAVITY` 则用（判断振动是否以竖直为主），无则跳过该判据 |
| 陀螺仪 | 有则参与「手持抑制」与姿态稳定性判别，无则自动降级 |
| 屏幕分辨率 / 密度 | 圆点抗锯齿用 `fwidth` 按**像素**计算过渡宽度，任何分辨率、DPI、球尺寸下都是 1px 过渡，无需配置 |
| 跨版本兼容 | 类名/字段名/方法名都有多套备选（`mInstances`/`mAntiDizzyInstances`、`handleMotionSensorEvent`/`handleSensorEvent` 等），并在日志里打印实际解析结果 |
| 多显示设备（折叠屏） | 遍历 `mInstances`，每个显示独立控制 |

启动日志会打印实际适配结果，例如：

```
reflect: handle=true settings=true needDraw=true view=true instField=mInstances
shader hook installed
shader: vertex patched (vLocal out)
shader: fragment patched (fwidth AA)
ready: source=LINEAR_ACCELERATION gyro=true
```

---

## 二、圆点抗锯齿（自适应任意分辨率）

### 为什么会有锯齿

原厂 `AntiDizzyView` 用 GLES 画「单位圆三角形扇」：

```glsl
// 顶点着色器：position 是单位圆上的坐标，由 proj_matrix 做平移+缩放
gl_Position = proj_matrix * position;

// 片元着色器：纯色填充，没有任何边缘处理
gl_FragColor = color;
```

片元着色器既不知道"离圆心多远"，也没做边缘混合 → 圆的边缘就是几何多边形的硬切边界，
在高 DPI 屏幕上（球的像素直径约 40px）看得很明显。

### 怎么修的

hook `GLES20.glShaderSource`，在 shader 编译前换成等价但带边缘平滑的版本：

```glsl
// 顶点：额外把单位圆坐标传给片元
varying vec2 vLocal;
void main() {
  vLocal = position.xy;
  gl_Position = proj_matrix * position;
}

// 片元：用 length(vLocal) 得到归一化半径，边缘 1px 过渡
#extension GL_OES_standard_derivatives : enable
precision mediump float;
uniform vec4 color;
varying vec2 vLocal;
void main() {
  float d = length(vLocal);
  float w = max(fwidth(d), 0.002);          // ← 关键：1 像素在"半径单位"下有多宽
  float a = 1.0 - smoothstep(1.0 - w, 1.0, d);
  if (a <= 0.004) discard;
  gl_FragColor = vec4(color.rgb, color.a * a);
}
```

**为什么这样就通用了**：`fwidth(d)` 由 GPU 自动算出"相邻像素间 d 的变化量"，
所以过渡带永远正好覆盖 1 个物理像素 —— 不管屏幕是 1080p 还是 2K、不管球是 20px 还是 60px、
不管是直屏还是折叠屏，边缘都是 1px 抗锯齿，**不需要任何按机型的分辨率配置**。

### 失败保护

- 替换前会用当前 GL context **试编译**一次，编译不过就保留原 shader（不会把"画不出来"当副作用）；
- 如果 GPU 不支持 `GL_OES_standard_derivatives`（极罕见），自动退到固定 4% 半径宽度的版本；
- 顶点着色器与片元着色器**成对替换**：顶点没成功就绝不单独换片元（避免 varying 不匹配导致链接失败）。

实测（1272×2772 屏，球径 39px，圆心所在行逐像素采样）：

| | 边缘像素 |
|---|---|
| 修复前 | `(152,158,170)` → `(63,125,220)` 直接跳变（硬切） |
| 修复后 | `(152,158,171)` → `(135,152,181)` → `(109,144,196)` → `(82,132,210)` → 纯蓝（渐变） |

---

## 三、「仅车辆运动时显示」：只认真实的车辆运动

原厂靠 `car scene` 传感器判断"是否在车上"。本模块用手机传感器复现，并针对
**手持误触发**做了专门设计。

### 判据（`VehicleDetector`，3 秒窗口，每 0.5s 评估）

| 特征 | 含义 | 车辆 | 手持微动 | 走路 |
|---|---|---|---|---|
| `rms` | 去重力后加速度交流分量（路面振动） | 0.3 ~ 1.5 | 0.1 ~ 0.5 | 0.3 ~ 1.5 |
| `low` | **1~6Hz 能量占比**（6Hz 二阶低通 / 总能量） | **0.80 ~ 0.86** | 0.55 | 0.72 |
| `vert` | 振动中沿重力方向的能量占比 | 1.0 | 1.0 | 1.0 |
| `rot` | 陀螺角速度 RMS（姿态稳定性） | **0.04 ~ 0.08** | **0.35** | 0.44 |
| `step` | 重力方向分量自相关在 1.2~2.8Hz 的峰值（步态） | **0.08 ~ 0.20** | 0.73 ~ 0.88 | 0.78 ~ 0.97 |

**进入条件（全部满足，且连续 8 秒）**

```
rms > 0.12  &&  low > 0.55  &&  vert > 0.45  &&  rot < 0.12  &&  step < 0.45  &&  未被手持抑制
```

**退出条件（任一满足，且连续 5 秒）**

```
rms < 0.05  ||  low < 0.35  ||  rot > 0.30  ||  step > 0.45
```

### 关键改动（相对 v4.0）

1. **陀螺从"触发条件"改成"否决条件"**。v4.0 里"角速度大 = 在转弯 = 在车上"，结果拿起手机、
   转手腕都会触发 —— 这正是"稍微移动就出现"的主因。现在 `rot` 必须**足够小**才可能进入。
2. **新增低频能量占比 `low`**。车辆路面激励集中在 1~6Hz；手抖在 8~12Hz。
   用 6Hz 二阶低通把两者分开。
3. **新增手持抑制**。检出 `rot > 0.6 rad/s`（明显腕部动作）或 `mag > 4.0 m/s²`（剧烈冲击）后，
   **8 秒内禁止进入**车辆态。避免"放下手机瞬间被判在车上"。
4. **进入时间 5s → 8s**，并要求窗口（3s）完全填满才评估，减少偶发尖峰。

### 实测（设备上合成数据 + 真实静止）

```
手持微动  state: veh=false  rms=0.16 low=0.55 rot=0.35 step=0.73   ← 拒绝
车辆行驶  state: veh=true   rms=0.36 low=0.86 rot=0.06 step=0.08   ← 接受
走路      state: veh=false  rms=0.40 low=0.72 rot=0.44 step=0.78   ← 拒绝
真实静止  state: veh=false  rms=0.01 low=0.90 rot=0.00 step=0.18   ← 拒绝（alpha=0）
```

### 可调参数（`Settings.Secure`，改完约 2 秒生效）

```bash
# 振动能量
adb shell settings put secure motion_cues_veh_rms   0.12   # 进入阈值（越小越灵敏）
adb shell settings put secure motion_cues_veh_stop  0.05   # 退出阈值
# 频段与姿态
adb shell settings put secure motion_cues_veh_low   0.55   # 低频占比下限（调高更严格，更抗手抖）
adb shell settings put secure motion_cues_veh_rot   0.12   # 陀螺上限（调小更严格；车内抖动大可调大到 0.18）
adb shell settings put secure motion_cues_veh_step  0.45   # 步态拒绝阈值（调小更容易判为走路）
# 时间
adb shell settings put secure motion_cues_veh_hold  8      # 连续满足多少秒才进入
```

> 如果**真车上进不去**，先把 `motion_cues_veh_rot` 调到 `0.18~0.22`（手机放在软座椅上会轻微晃动）；
> 如果**手持还是会误触发**，把 `motion_cues_veh_low` 调到 `0.65`、`motion_cues_veh_rot` 调到 `0.10`。

---

## 四、「常驻显示」的数据输入

| 项 | 处理 |
|---|---|
| 数据源 | `TYPE_LINEAR_ACCELERATION`（系统级去重力，延迟低），无则加速度计 + 软件低通 |
| 采样率 | 100Hz |
| 静止噪声 | 0.02 m/s² 死区，静止不抖 |
| 幅度 | `motion_cues_gain` 缩放（0.2~10） |
| 视图重建 | 切模式后 alpha / 防截图跟随视图实例重新应用 |

**注意**：检测器吃的是**死区和增益之前的原始数据**，所以 `motion_cues_gain` 不会影响车辆识别。

```bash
adb shell settings put secure motion_cues_gain 1.5   # 圆点摆动幅度
```

---

## 五、安装

1. KernelSU → 模块 → 从本地安装 → `k15_motion_cues_port_v5.0.zip` → 重启
2. 打开 **LSPosed** → 模块 → 勾选 **AntiDizzy Bridge** → 作用域只勾 **系统框架** → 重启
3. 设置 → 辅助功能 → 晕动舒缓显示 → 开启

> 第 2 步必须做。KernelSU 无法代写 LSPosed 配置（它是 SQLite 库），这是唯一的手动步骤。
>
> **更新模块时注意**：`pm install` 会改变 APK 路径，LSPosed 在 zygote 里缓存的是旧路径，
> 所以**更新 APK 后必须整机重启**（只重启 system_server 不够）。

---

## 六、验证

```bash
# 特性是否补上（脚本日志）
adb shell su -c 'cat /data/local/tmp/motion_cues_port.log'
#   patched: /system_ext/etc/permissions/com.oplus.features_config.xml
#   model=PMG110 rom=V16.1.0 done

# 注入 / 抗锯齿是否生效
adb shell su -c 'grep -i -e shader -e ready: /data/adb/lspd/log/modules_*.log | tail -6'
#   shader hook installed
#   shader: vertex patched (vLocal out)
#   shader: fragment patched (fwidth AA)
#   ready: source=LINEAR_ACCELERATION gyro=true

# 车辆识别的实时特征
adb shell su -c 'grep state: /data/adb/lspd/log/modules_*.log | tail -3'
#   state: veh=false rms=0.01 low=0.90 vert=1.0 rot=0.00 step=0.18 sup=false mode=1 test=0 ...

# 原厂视图在出帧
adb shell logcat -d | grep -i AntiDizzyView | tail -3
```

---

## 七、调试开关

```bash
# 合成数据（不用开车也能验证识别逻辑）
#   0=真实传感器  1=正弦振荡  2=车辆振动  3=走路  4=手持微动
adb shell settings put secure motion_cues_bridge_test 2
adb shell settings put secure motion_cues_bridge_test 0

# 模式
adb shell settings put secure oplus_customize_antidizzy_mode 2   # 2=常驻  1=仅车辆运动
```

对照预期：

| test | 场景 | `veh` 应为 |
|---|---|---|
| 0 | 真实传感器（静止） | false |
| 1 | 正弦振荡（仅验证渲染链路） | 由幅度决定 |
| 2 | 车辆振动 | **true** |
| 3 | 走路 | **false** |
| 4 | 手持微动 | **false** |

模块会关闭原厂的防截图标志，圆点能出现在截图/录屏里（原厂默认截不到）。

---

## 八、已知限制

- **前提是固件里得有 AntiDizzy 实现**（`oplus-services.jar` 里有
  `com.android.server.display.feature.antidizzy.*`）。完全没有这套代码的老机型，模块无能为力
  （日志会打印 `找不到 AntiDizzyManager`）。
- `car scene` 是**行为近似**，不等同原厂传感器融合（原厂可能结合 GPS/地磁/车速/车机连接）。
  严格来说原厂判断的是"场景"，本模块判断的是"振动特征"。
- 抗锯齿依赖 `GL_OES_standard_derivatives`（GLES2 可选项，实际支持率接近 100%）；
  不支持时退化为固定宽度平滑，效果略差但仍优于原厂。
- LSPosed 作用域需手动勾选。
- OTA 后配置文件内容会变，脚本每次开机重新探测重建，通常无需干预。

---

## 九、卸载：模块自己收拾干净

**直接在 KernelSU 里点删除就行，不需要再敲命令。**

### 清理时机（双保险）

**第一道：删除模块的瞬间** —— KernelSU 会执行模块内的 `uninstall.sh`：

- 卸载 Xposed 桥接 APK（**这一步同时停掉 LSPosed 注入**）
- 删除 11 个 Settings 键（开关、模式、调参）
- **立即 `umount` 三个 bind mount** —— 分区文件当场恢复原样，不用等重启
- 清 `/data/local/tmp` 下的日志、APK 副本、临时 XML

**第二道：如果第一道没跑成** —— 比如直接 `rm -rf` 删目录、或 KSU 版本不支持 `uninstall.sh`。
模块在 `/data/adb/service.d/motion_cues_cleanup.sh` 留了一个「墓碑」，每次开机会检查：

| 情况 | 行为 |
|---|---|
| 模块还在 | 立即退出，不做任何事（约 1ms 开销） |
| 模块已删 | 执行同样的清理，**成功后自删** |

墓碑之所以放在模块目录之外，是因为模块一删，目录里的代码就没了，没有任何东西能在下次开机执行。

### 为什么分区文件一定恢复

三个特性配置文件用的是 `mount --bind`，不是直接改文件。分区是只读 EROFS，物理上也写不进去。
`umount` 之后看到的就是原文件。

### 唯一可能留下的一处

`/data/adb/lspd/config/modules_config.db` 里可能还留着本模块的一行登记。
APK 卸载后 LSPosed 不会再加载它，这行记录只是界面里一条灰色失效项，**无功能影响**。
想清掉就进 LSPosed 界面取消勾选——不建议用脚本直接改这个库，LSPosed 运行中改会冲突。

### 手动确认（可选）

```bash
# 分区原文件应为 0（说明 bind mount 已卸、原文件未被改）
adb shell su -c 'grep -c motion_sickness /system_ext/etc/permissions/com.oplus.features_config.xml'

# 包应不存在
adb shell pm path com.workbuddy.antidizzybridge

# 墓碑应已自删（应输出 No such file）
adb shell su -c 'ls /data/adb/service.d/motion_cues_cleanup.sh'
```
