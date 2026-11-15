# 让欧加手机用上「晕动舒缓显示」：一个 KernelSU + LSPosed 模块的折腾记录

> 适配 ColorOS 16 全系（OPPO / 一加 / realme），自动识别机型与传感器，
> 驱动原厂 AntiDizzy 渲染，补上原厂缺失的边缘抗锯齿，重写车辆运动识别。

---

## 先说结论

坐车看手机头晕的人应该懂那种感觉：眼睛盯着屏幕，身体却在加速、转弯、颠簸，视觉和前庭觉对不上，十几分钟就难受。

ColorOS 16 里 OPPO 做了「晕动舒缓显示」（设置 → 辅助功能）。开启后屏幕左右边缘会浮现一排蓝色小圆点，随车辆运动方向做反向滑动，给视觉系统一个"身体在动"的参照，缓解晕动。

问题是这功能被机型白名单锁死了。白名单里的机器能用，名单外的（比如 K15 Pro+）设置里那个开关是灰的，点不动。

这个模块做的事，就是把这个开关解开，并且把原厂缺的那部分补上。

---

## 一、不是"移植"，是"解锁 + 补全"

一开始我也以为要从 Find X9 的 ROM 里搬东西过去。把 X9 的 OTA 解包（`payload.bin` → `system` / `system_ext` / `my_product` / `vendor` / `odm` / `my_stock` 全解出来，EROFS 格式，最后是拿手机自己的 `fsck.erofs` 解的），逐分区对比完才发现方向错了：

**K15 Pro+ 的固件里，这套功能本来就是完整的。**

`oplus-services.jar` 里躺着全套实现：

```
com.android.server.display.feature.antidizzy.AntiDizzyManager
                                        .AntiDizzyView        ← GLES 绘制
                                        .AntiDizzyBall
                                        .AntiDizzyMotionAlgorithm
                                        .AntiDizzySensorManager
                                        .scenes.*             ← 游戏/地图/温控场景
```

`Settings.apk` 里有完整的设置页、Lottie 预览动画、`settings_map.xml`；`OplusEyeProtect.apk`（明眸护眼）里也有入口。跟 X9 的对应部分**逐类对比，实现是一致的**。

缺的只有两样东西：

| 缺什么 | 性质 |
|---|---|
| `com.oplus.features_config.xml` 里的一行 `<oplus-feature name="oplus.hardware.display.motion_sickness"/>` | 软件门控，补一行就行 |
| 两个 OPPO 私有传感器 `33171091`(car motion) / `33171092`(car scene) | 硬件差异，绕不过去 |

第一行决定 `isSupportMotionSicknessRelief()` 的返回值——它走 `OplusFeatureConfigManager.hasFeature()`，本质是 SystemConfig 从权限 XML 里解析出来的 system feature。没有这行，设置页开关是灰的，system_server 里的 AntiDizzy 服务压根不初始化。

第二样才是真正的麻烦。原厂的球要靠这两颗传感器的事件才会从 `BallState 0` 激活、淡入。K15 Pro+ 的 sensorservice 里私有类型编号从 33171082 直接跳到 33171094，中间是空的。

---

## 二、适配：不是给某一台机器做的

模块里没有写死任何机型的配置。开机时脚本自己判断该改哪里、该不该改。

| 适配项 | 做法 |
|---|---|
| **特性文件路径** | 依次探测 7 个候选路径（`/system_ext/etc/permissions/`、`/my_product/etc/{permissions,extension}/` 等），谁存在就补谁。内容在开机时由 `awk` 现场生成，不依赖机型。实测在 K15 Pro+ 上一次命中 3 个文件（还找出一个我人工排查时漏掉的 `my_product/etc/permissions/com.oplus.features_config.xml`） |
| **原厂已有车辆传感器** | 扫描 `SensorManager` 全表，命中 `33171091/33171092` 或名称含 `car motion`/`car scene` → **直接退出，不注入**，保持原厂行为。所以对 Find X9 这类原生支持的机器是安全的，不会互相打架 |
| **运动数据源** | 优先 `TYPE_LINEAR_ACCELERATION`（系统已去重力，延迟低），没有则回退加速度计 + 软件低通去重力 |
| **重力方向** | 有 `TYPE_GRAVITY` 就用（判断振动是否以竖直为主），没有就跳过这条判据 |
| **陀螺仪** | 有则参与姿态稳定性判别与手持抑制，无则自动降级 |
| **屏幕分辨率 / DPI** | 抗锯齿用 `fwidth` 按**像素**计算过渡宽度，任何分辨率、密度、球尺寸下都是 1px 过渡，零配置 |
| **跨版本兼容** | 类名 / 字段名 / 方法名都有多套备选（`mInstances` / `mAntiDizzyInstances`、`handleMotionSensorEvent` / `handleSensorEvent` 等），启动日志打印实际解析结果，方便排障 |
| **折叠屏 / 多显示** | 遍历 `mInstances`，每个显示独立控制 |

启动时日志会把适配结果打出来，不用猜：

```
reflect: handle=true settings=true needDraw=true view=true instField=mInstances
shader hook installed
shader: vertex patched (vLocal out)
shader: fragment patched (fwidth AA)
ready: source=LINEAR_ACCELERATION gyro=true
```

---

## 三、算法之一：车辆识别，不是"有振动就算在车上"

原厂判断"是否在车上"靠的是 `car scene` 传感器（可能还融合了车机连接、GPS、地磁）。没有这颗传感器，就得用普通传感器复现这个判断。

第一版我写得很天真：振动能量超过阈值 + 没在走路 = 在车上。

结果你也猜到了——**手机拿在手上稍微动一下就亮，只有完全放在桌上一动不动才消失**。

问题出在把陀螺仪用反了。第一版里"角速度大 = 在转弯 = 在车上"是**准入条件**，可拿起手机、转个手腕、点下屏幕，角速度全都不小。

现在的判据是五个特征，陀螺变成了**否决条件**：

| 特征 | 含义 | 车辆 | 手持微动 | 走路 |
|---|---|---|---|---|
| `rms` | 去重力后加速度交流分量（路面振动强度） | 0.36 | 0.16 | 0.40 |
| `low` | **1–6Hz 能量占比**（6Hz 二阶低通 / 总能量） | **0.86** | 0.55 | 0.72 |
| `vert` | 振动中沿重力方向的能量占比 | 1.0 | 1.0 | 1.0 |
| `rot` | 陀螺角速度 RMS（姿态稳定性） | **0.06** | **0.35** | 0.44 |
| `step` | 重力方向分量自相关在 1.2–2.8Hz 的峰值 | **0.08** | **0.73** | 0.78 |

**进入**（全部满足，且连续 8 秒）：

```
rms > 0.12 && low > 0.55 && vert > 0.45 && rot < 0.12 && step < 0.45 && 未被手持抑制
```

**退出**（任一满足，连续 5 秒）：

```
rms < 0.05 || low < 0.35 || rot > 0.30 || step > 0.45
```

三个判据分工明确：

- **`low`（低频占比）**：路面激励集中在 1–6Hz，手抖在 8–12Hz。用 6Hz 二阶低通把两者分开，这是纯频域判据，跟幅度无关。
- **`rot`（陀螺）**：手机固定在支架上，行驶中姿态几乎不动（0.06）；手持必然抖（0.35）。
- **`step`（步态）**：走路是 1.5–2Hz 的周期冲击，自相关峰值 0.8 以上；车辆振动是宽带的、没周期，接近 0。这条是最干净的——实测车辆和走路的 `rms` 几乎一样（0.36 vs 0.40），只有 `step` 能分开。

另外加了**手持抑制**：检出 `rot > 0.6 rad/s`（明显腕部动作）或 `mag > 4.0 m/s²`（剧烈冲击）后，**8 秒内禁止进入**车辆态。这样"把手机从桌上拿起来"和"放下手机"的瞬间不会被误判。

设备上实测（合成数据 + 真实静止）：

```
手持微动  veh=false  rms=0.16 low=0.55 rot=0.35 step=0.73   ← 拒绝
车辆行驶  veh=true   rms=0.36 low=0.86 rot=0.06 step=0.08   ← 接受
走路      veh=false  rms=0.40 low=0.72 rot=0.44 step=0.78   ← 拒绝
真实静止  veh=false  rms=0.01 low=0.90 rot=0.00             ← 圆点 alpha=0
```

还有个小坑：`mode=1`（仅车辆运动）时**原厂根本不创建浮层**，它在等 `car scene`。所以检测到车辆后，还得反射调一次 `createAntiDizzyView()` 把视图拉起来。

---

## 四、算法之二：给原厂圆点补上抗锯齿

圆点边缘的锯齿是原厂的问题，不是移植引入的。

反汇编 `AntiDizzyView` 的 shader 初始化，拿到原厂 GLSL：

```glsl
// 顶点着色器
attribute vec4 position;
uniform mat4 proj_matrix;
void main() {
  gl_Position = proj_matrix * position;
}

// 片元着色器
precision mediump float;
uniform vec4 color;
void main() {
  gl_FragColor = color;      // ← 纯色硬填充，没有任何边缘处理
}
```

几何是 `generateCircleVertices()` 生成的**单位圆三角形扇**（`cos/sin(2πi/n)`，圆心在原点），位置和大小靠 `Matrix.translateM` + `scaleM` 放置。

所以 `position.xy` 本身就是归一化半径——这让修法变得很干净。

Hook `GLES20.glShaderSource`，在编译前换成等价但带边缘平滑的版本：

```glsl
// 顶点：把单位圆坐标传给片元
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
  float w = max(fwidth(d), 0.002);          // ← 关键
  float a = 1.0 - smoothstep(1.0 - w, 1.0, d);
  if (a <= 0.004) discard;
  gl_FragColor = vec4(color.rgb, color.a * a);
}
```

**`fwidth(d)` 是这里的核心**：GPU 自动算出"相邻像素之间 `d` 变化了多少"，所以过渡带永远正好覆盖一个物理像素。

这意味着**不需要任何按机型的分辨率配置**——1080P 和 2K 一样，球径 20px 和 60px 一样，直屏和折叠屏一样。这也正是我不愿意用"按 DPI 配比例"方案的原因：那种做法换台机器就得重调。

实测（1272×2772，球径 39px，在圆心所在行逐像素采样）：

| | 边缘像素序列 |
|---|---|
| 修复前 | `(152,158,170)` → `(63,125,220)` —— 直接跳变 |
| 修复后 | `(152,158,171)` → `(135,152,181)` → `(109,144,196)` → `(82,132,210)` → 纯蓝 |

正好 1 个像素的渐变。斜边方向上过渡带会自然变宽，那是几何上正确的（边缘法线方向才是 1px）。

**失败保护**也做了：替换前会用当前 GL context **试编译**一次，编译不过就保留原 shader，不会把"画不出来"当副作用；GPU 不支持 `GL_OES_standard_derivatives` 时退化成固定 4% 半径宽度；顶点和片元必须成对替换，顶点没成功就绝不单独换片元（否则 varying 不匹配会链接失败）。

---

## 五、迭代过程：五个版本，踩了三个大坑

### v1：以为只缺一个开关

补上那行 `<oplus-feature>`，重启，设置页开关亮了，子选项也能点了。

**但屏幕上什么都没有。**

### v2：以为要自己画

查 `dumpsys display` 发现原厂 `AntiDizzyView` 已经创建、`mRunning=true`、`mPrepareSuccess=true`，但 `mSurfaceAlpha = 0.0`，`SENSOR_REGISTRATION_MAP={33171091=[], 33171092=[]}`——传感器注册全空。

于是自己写了个悬浮窗应用，用标准加速度计驱动边缘圆点。能用，但那不是原厂的东西。

### v3：发现原厂一直在跑，只是你看不见

关键线索是 `BufferQueueProducer: [AntiDizzyView#51] fps=60.2`。

**原厂视图一直在以 60fps 出帧。** 两个东西挡住了它：

- `mSurfaceAlpha = 0.0` —— 整层透明
- `mSkipScreenshot = true` —— 所以截图永远拍不到（这是我 v2 误判的直接原因）

而 alpha 为 0 的原因，就是 `handleMotionSensorEvent()` 收不到事件。

所以方案改成：**不替换任何方法，只往 system_server 里送真实加速度**。

写了个 LSPosed 模块，注册标准加速度计（100Hz），低通估重力取线性加速度，按原厂的数据布局（反汇编出来的：`values[1][2]` 和 `values[4][5]` 是两套 x/y，`values[8][9][10]` 是 xyz）构造 `SensorEvent`，反射调用 `handleMotionSensorEvent()`。

之后全部走原厂代码：`AntiDizzyMotionAlgorithm` → `AntiDizzyBall` → `AntiDizzyView`（GLES 绘制）。

唯一需要"代劳"的是 alpha——原厂靠一个内部 handler 消息抬透明度，注入端拿不到，就直接调 `updateSurfaceAlpha(1f)`。

**成了。** 原厂的圆点出现在设置界面之上，位置不规则、随加速度偏移，正是原厂算法的特征。

### v4：通用化，但车辆识别太敏感

把写死的配置改成开机探测，加上车辆识别（振动能量 + 步态 + 转向角速度）。

陀螺被当成"转向证据"用在了准入条件里——这就是"稍微移动就出现"的根源。

### v5：修两处

陀螺改成否决条件，加入低频占比、重力方向、手持抑制；同时补上 shader 抗锯齿。

中途还把 system_server 搞崩了两次：`tryCompile` 里调 `glShaderSource`，结果被自己的 hook 又拦了一次，无限递归到栈溢出。加 `ThreadLocal` 防重入解决。

---

## 六、安装

1. KernelSU → 模块 → 从本地安装 → 重启
2. 打开 **LSPosed** → 模块 → 勾选 **AntiDizzy Bridge** → 作用域只勾 **系统框架** → 重启
3. 设置 → 辅助功能 → 晕动舒缓显示 → 开启

**第 2 步必须手动做。** LSPosed 的配置是个 SQLite 库，KernelSU 没法代写，这是整个流程里唯一需要动手的地方。

> 更新模块时注意：`pm install` 会改变 APK 路径，而 LSPosed 在 zygote 里缓存的是旧路径。**更新后必须整机重启**，只重启 system_server 不够。

---

## 七、调参

### 车辆识别

```bash
# 真车上进不去 → 放宽陀螺门槛（手机放软座椅上会轻微晃）
adb shell settings put secure motion_cues_veh_rot 0.18

# 手持还是会误触发 → 收紧
adb shell settings put secure motion_cues_veh_rot 0.10
adb shell settings put secure motion_cues_veh_low 0.65

# 其他
adb shell settings put secure motion_cues_veh_rms  0.12   # 振动能量下限
adb shell settings put secure motion_cues_veh_stop 0.05   # 退出阈值
adb shell settings put secure motion_cues_veh_hold 8      # 连续满足多少秒才进入
```

### 常驻模式

```bash
adb shell settings put secure motion_cues_gain 1.5   # 圆点摆动幅度
```

### 验证不用开车

```bash
# 0=真实传感器  1=正弦  2=车辆振动  3=走路  4=手持微动
adb shell settings put secure motion_cues_bridge_test 2
adb shell settings put secure motion_cues_bridge_test 4

# 看实时特征
adb shell su -c 'grep state: /data/adb/lspd/log/modules_*.log | tail -3'
```

---

## 八、已知限制（先说清楚）

- **前提是固件里得有 AntiDizzy 实现**。`oplus-services.jar` 里必须有 `com.android.server.display.feature.antidizzy.*`。完全没有这套代码的老机型，模块无能为力——日志会打印"找不到 AntiDizzyManager"。**刷之前建议先确认一下**。
- **`car scene` 是行为近似**，不等同原厂传感器融合。原厂判断的是"场景"（可能结合车机连接、GPS、地磁），本模块判断的是"振动特征"。会有差异，比如在公交/地铁上原厂可能判定为"非驾驶场景"而本模块会判为在车上。
- **抗锯齿依赖 `GL_OES_standard_derivatives`**（GLES2 可选项，实际支持率接近 100%）。不支持时退化为固定宽度平滑，效果略差但仍好于原厂。
- **LSPosed 作用域需手动勾选**，无法自动化。
- OTA 后配置文件内容会变，脚本每次开机重新探测重建，通常无需干预。

---

## 九、卸载

1. LSPosed 停用 AntiDizzy Bridge
2. KernelSU 删除 `motion_cues_port` 模块
3. 重启，然后：

```bash
adb shell pm uninstall com.workbuddy.antidizzybridge
adb shell settings put secure oplus_customize_antidizzy_enable 0
```

---

## 最后

做这个模块的过程中最有意思的一点是：**大部分时候你以为要"移植"，其实只是要"解锁"**。

厂商在固件里塞了完整实现，然后用一行特性声明和一颗传感器把入口关掉。找到那行声明在哪、找到那颗传感器在卡哪个环节，比从零写一个渲染器要划算得多——而且出来的东西是原厂的，视觉和手感天然一致。

如果这个模块帮到你，欢迎反馈问题。尤其是**不同机型上的车辆识别表现**——这一块我只能在自己这一台机器上验证，阈值可能需要按机型微调。

---

*风险提示：涉及 root、KernelSU、LSPosed 操作，刷机有风险。模块只做配置文件 bind mount 和 system_server 内的传感器数据注入，不改动任何分区内容，卸载即恢复。但请自行评估风险，出问题不负责。*
