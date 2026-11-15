# 给欧加手机补全「晕动舒缓显示」——KernelSU + LSPosed 模块

ColorOS 16 的「晕动舒缓显示」被机型白名单锁死，名单外的机器（如 K15 Pro+）设置里开关是灰的。拆包后发现固件里 `oplus-services.jar` 的 AntiDizzy 实现、Settings 的设置页与预览动画全都齐全，只缺一行特性声明和两颗 OPPO 私有传感器。模块就是补这两样，圆点仍由原厂 `AntiDizzyView` 绘制，视觉与原厂一致。

## 实现原理

分三层，每层都尽量少碰原厂代码。

**1. 解锁特性开关**：ColorOS 的功能门控走 `hasOplusFeature()`，数据来自 SystemConfig 解析 `/system_ext/etc/permissions/*.xml` 里的 `<oplus-feature>` 标签。模块开机时用 `awk` 往原文件里插入缺失的那一行，再 `mount --bind` 覆盖——**不改分区、不动原文件，卸载即恢复**。KernelSU 的 magic mount 不覆盖 `/system_ext`，所以脚本里自己做了 bind mount。

**2. 补上传感器输入**：这是唯一需要进进程的部分。LSPosed 模块在 system_server 里起一个泵线程，注册标准加速度计与陀螺，低通估重力取线性加速度，按反汇编出来的原厂数据布局（`values[1][2]`、`values[4][5]` 是两套 x/y，`values[8][9][10]` 是 xyz）构造 `SensorEvent`，反射调用 `handleMotionSensorEvent()`。**不替换任何原厂方法，只往里喂数据**——风险最低，也不会跟系统更新打架。

**3. 渲染交给原厂**：之后全走原厂链路，`AntiDizzyMotionAlgorithm` 算位移 → `AntiDizzyBall` 更新球位置 → `AntiDizzyView` 用 GLES 绘制。模块只额外做两件事：把 alpha 抬到 1（原厂靠内部 handler 消息，注入端拿不到），以及 hook `glShaderSource` 补抗锯齿。

## 适配优势

- **不写死机型**：开机探测 7 个候选配置路径，谁存在就补谁。实测本机一次命中 3 个文件，连人工排查时漏掉的那个也找出来了。
- **不动原生支持的机器**：先扫 `SensorManager` 全表，命中 `car motion` / `car scene` 就直接退出、不注入，对 Find X9 这类机型安全。
- **传感器逐项降级**：线性加速度优先、没有就用加速度计去重力；有重力传感器才算振动方向；无陀螺也能跑。
- **跨版本兼容**：类名 / 字段名 / 方法名都有多套备选，启动日志直接打印实际适配结果，方便排障。

## 算法优势

**1. 圆点抗锯齿（原厂没有）**

反汇编发现原厂片元着色器只有一句 `gl_FragColor = color`，纯色硬填充，边缘必然锯齿。模块 hook `glShaderSource`，改用单位圆坐标算归一化半径，再用 `fwidth` 做 1 像素过渡——**过渡带由 GPU 按像素自动计算，1080P / 2K、球径 20px / 60px 全部自适应，不需要任何按机型的分辨率配置**。实测边缘从「背景色直接跳到纯蓝」变成 3~4 级渐变。

**2. 车辆识别（原厂靠私有传感器，这里用普通传感器复现）**

不是"有振动就算在车上"。五个特征联合判断：振动能量、**1–6Hz 低频占比**（车振在低频、手抖在 8–12Hz）、重力方向能量占比、**陀螺角速度 RMS**、**步频自相关**。关键是陀螺是**否决**条件而不是触发条件，另有 8 秒手持抑制。

实测数据：

- 车辆行驶：`rot=0.06  step=0.08` → 显示
- 手持微动：`rot=0.35  step=0.73` → 拒绝
- 走路：`step=0.78` → 拒绝

## 开发过程

五个版本。v1 以为只缺个开关，开关亮了但没圆点；v2 发现渲染器缺传感器输入，自己写了个替代浮层；v3 才查到原厂视图一直在 60fps 出帧，只是 `alpha=0` 且设了防截图，于是改成往 system_server 注入加速度，让原厂链路自己跑起来；v4 做成通用版，但车辆识别太敏感；v5 修识别逻辑 + 补上抗锯齿。

## 安装

KernelSU 装模块 → LSPosed 勾选 **AntiDizzy Bridge**、作用域只选「系统框架」→ 重启 → 设置 → 辅助功能 → 晕动舒缓显示。

LSPosed 那步必须手动（配置是 SQLite 库，KernelSU 写不了）。更新模块后需**整机重启**，只重启 system_server 不够。

**前提**：固件里必须有 AntiDizzy 实现（`oplus-services.jar` 含 `com.android.server.display.feature.antidizzy.*`），完全没有这套代码的老机型模块帮不上。
