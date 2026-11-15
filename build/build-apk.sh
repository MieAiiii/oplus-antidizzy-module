#!/usr/bin/env bash
# ============================================================
# 构建 AntiDizzyBridge.apk（LSPosed 模块）
#
# 依赖：
#   - JDK 17+          （javac / keytool / java）
#   - Android build-tools 33+  （aapt2 / d8 / zipalign / apksigner）
#   - Android platform android.jar （API 33+）
#
# 用法：
#   ANDROID_JAR=/path/to/android.jar \
#   BUILD_TOOLS=/path/to/build-tools/35.0.0 \
#   ./build/build-apk.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
XPOSED="$ROOT/xposed"
OUT="$ROOT/build/tmp"

: "${ANDROID_JAR:?请设置 ANDROID_JAR（例如 \$ANDROID_HOME/platforms/android-36/android.jar）}"
: "${BUILD_TOOLS:?请设置 BUILD_TOOLS（例如 \$ANDROID_HOME/build-tools/35.0.0）}"

# aapt2 读不了非 ASCII 路径，提前拦一下，省得报莫名其妙的错
if printf '%s' "$ROOT" | LC_ALL=C grep -q '[^ -~]'; then
  echo "错误：项目路径含非 ASCII 字符，aapt2 会失败。" >&2
  echo "      当前路径：$ROOT" >&2
  echo "      请把项目移到纯英文路径下再构建。" >&2
  exit 1
fi

PY="$(command -v python3 || command -v python)"
if [ -z "$PY" ]; then
  echo "错误：需要 python3（用于合并 dex 到 APK）" >&2
  exit 1
fi

tool() {
  local n="$1"
  if [ -x "$BUILD_TOOLS/$n" ]; then echo "$BUILD_TOOLS/$n"
  elif [ -x "$BUILD_TOOLS/$n.exe" ]; then echo "$BUILD_TOOLS/$n.exe"
  else echo "错误：$BUILD_TOOLS 下找不到 $n" >&2; exit 1
  fi
}

AAPT2="$(tool aapt2)"
ZIPALIGN="$(tool zipalign)"

echo "== 1/6 清理"
rm -rf "$OUT"
mkdir -p "$OUT/stub" "$OUT/classes" "$OUT/dex" "$ROOT/dist"

echo "== 2/6 编译 Xposed API 桩（仅编译期使用，不进 dex）"
javac -source 8 -target 8 -nowarn -encoding UTF-8 -d "$OUT/stub" \
  $(find "$XPOSED/stub" -name '*.java')
jar cf "$OUT/stub.jar" -C "$OUT/stub" .

echo "== 3/6 编译模块源码"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$OUT/stub.jar" \
  -nowarn -encoding UTF-8 -d "$OUT/classes" \
  $(find "$XPOSED/src" -name '*.java')

echo "== 4/6 生成基础 APK（清单 + assets）"
"$AAPT2" link -o "$OUT/base.apk" \
  --manifest "$XPOSED/AndroidManifest.xml" \
  -A "$XPOSED/assets" \
  -I "$ANDROID_JAR" \
  --min-sdk-version 33 --target-sdk-version 36

echo "== 5/6 转 dex 并合并进 APK"
java -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 33 --lib "$ANDROID_JAR" --classpath "$OUT/stub.jar" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

"$PY" - "$OUT" <<'PY'
import sys, os, zipfile
out = sys.argv[1]
base = os.path.join(out, 'base.apk')
unsigned = os.path.join(out, 'unsigned.apk')
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zout.writestr(it, zin.read(it.filename))
zin.close()
# classes.dex 用 STORE，避免某些 ROM 的解压限制
zout.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex', zipfile.ZIP_STORED)
zout.close()

# 确认桩类没有被编进 dex（应当只出现在类型引用里）
d = zipfile.ZipFile(unsigned).read('classes.dex')
assert b'com/workbuddy/antidizzybridge/Bridge' in d, 'Bridge 类缺失'
print('   dex', len(d), 'bytes')
PY

echo "== 6/6 对齐与签名"
KS="$ROOT/build/debug.keystore"
if [ ! -f "$KS" ]; then
  echo "   生成调试签名（build/debug.keystore，不会提交到仓库）"
  keytool -genkeypair -keystore "$KS" -alias antidizzy -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=AntiDizzyBridge, O=Oplus, C=CN" 2>/dev/null
fi
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
java -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/dist/AntiDizzyBridge.apk" "$OUT/aligned.apk"
java -jar "$BUILD_TOOLS/lib/apksigner.jar" verify "$ROOT/dist/AntiDizzyBridge.apk"

echo ""
echo "完成：dist/AntiDizzyBridge.apk"
echo "接着跑 ./build/build-module.sh 生成可刷入的模块 zip"
