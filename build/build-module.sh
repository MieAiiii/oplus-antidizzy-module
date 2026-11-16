#!/usr/bin/env bash
# ============================================================
# 把 module/ 目录和已构建的 APK 打成可刷入的 KernelSU 模块 zip
#
# 用法：
#   ./build/build-module.sh
#
# 产物：dist/oplus-antidizzy-module-<version>.zip
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/module"
DIST="$ROOT/dist"
APK="$MOD/files/AntiDizzyBridge.apk"

if [ ! -f "$APK" ]; then
  echo "错误：找不到 $APK，请先运行 ./build/build-apk.sh" >&2
  exit 1
fi

VER="$(grep '^version=' "$MOD/module.prop" | cut -d= -f2)"
OUT="$DIST/oplus-antidizzy-module-${VER}.zip"

PY="$(command -v python3 || command -v python)"
if [ -z "$PY" ]; then
  echo "错误：需要 python3" >&2
  exit 1
fi

mkdir -p "$DIST"
cp -f "$APK" "$DIST/AntiDizzyBridge.apk"

"$PY" - "$MOD" "$OUT" <<'PY'
import sys, os, zipfile, stat

src, out = sys.argv[1], sys.argv[2]
if os.path.exists(out):
    os.remove(out)

def mode(name):
    return 0o755 if name.endswith('.sh') else 0o644

with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, dirs, files in os.walk(src):
        for f in sorted(files):
            p = os.path.join(base, f)
            rel = os.path.relpath(p, src).replace('\\', '/')
            zi = zipfile.ZipInfo(rel)
            zi.external_attr = (stat.S_IFREG | mode(rel)) << 16
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, open(p, 'rb').read())

with zipfile.ZipFile(out) as z:
    for i in z.infolist():
        perm = (i.external_attr >> 16) & 0o777
        print(f"   {i.filename:<40} {i.file_size:>7}B  {oct(perm)}")
print('   total', os.path.getsize(out), 'bytes')
PY

echo ""
echo "完成：${OUT#$ROOT/}"
