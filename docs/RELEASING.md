# 维护与发布

## 日常改动

```bash
cd oplus-antidizzy-module
# 改代码 / 文档
git add -A
git commit -m "fix: 说明改了什么"
git push
```

推到 `main` 会触发 CI 做一次构建验证（不发布）。构建失败会在 Actions 页面标红。

## 本地构建与真机验证

改完脚本或 Java 后，**最好先在本地跑通再推**：

```bash
export ANDROID_JAR=/path/to/android.jar      # API 33+
export BUILD_TOOLS=/path/to/build-tools/35.0.0

./build/build-apk.sh       # → dist/AntiDizzyBridge.apk
./build/build-module.sh    # → dist/oplus-antidizzy-module-<ver>.zip
```

装到手机：

```bash
adb push dist/oplus-antidizzy-module-*.zip /data/local/tmp/
# 然后到 KernelSU 里安装，或直接用 magisk/ksud 命令
```

## 发版

改 `module/module.prop` 里的 `version` 和 `versionCode`，然后：

```bash
git add module/module.prop
git commit -m "release: v5.1"
git push

git tag v5.1
git push origin v5.1
```

推 tag 后 CI 会自动：

1. 编译 APK
2. 打包模块 zip
3. 创建 GitHub Release
4. 把 `dist/*.zip` 和 `dist/*.apk` 挂到 Release 上

**不要**再手动往 Release 传文件，会覆盖 CI 的产物。

## CI 的工作方式

`.github/workflows/build.yml`

| 触发 | 行为 |
|---|---|
| push 到 `main` | 构建验证，产物存 Actions artifact（保留 90 天） |
| push tag `v*` | 构建 + 自动创建 Release 并上传附件 |
| Pull Request | 构建验证 |
| 手动触发 | 同上，用于排查环境问题 |

构建环境：Ubuntu + JDK 17 + build-tools 35.0.0 + platform android-36。

## 签名密钥

APK 签名必须固定，否则新版本无法覆盖安装旧版本（`pm install -r` 会报签名不一致）。

密钥存在仓库 Secrets 里：

- 名称：`KEYSTORE_B64`
- 内容：`build/debug.keystore` 的 base64 编码
- keystore 密码 / key 密码：`android`（硬编码在 `build-apk.sh` 里）

CI 在构建前把它解码到 `build/debug.keystore`。**这个文件已被 `.gitignore` 排除，绝不要提交。**

更换密钥的后果：所有已安装用户必须先卸载模块和 APK 才能升级。除非密钥泄露，否则不要换。

## 目录约定

```
module/          改动这里会影响刷入行为
xposed/src/      改动这里会影响注入与算法
build/           改动这里会影响 CI
dist/            提交的是最近一次发布的产物，供直接下载
```

改完 `module/` 里的脚本后记得重新跑 `build-module.sh`，否则 `dist/` 里的 zip 还是旧的。
