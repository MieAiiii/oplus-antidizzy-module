#!/system/bin/sh
# 安装注入模块（Xposed 模块，首次需在 LSPosed 中启用并勾选「系统框架」，然后重启）
MODDIR=${0%/*}
LOG=/data/local/tmp/motion_cues_port.log
PKG=com.workbuddy.antidizzybridge
APK="$MODDIR/files/AntiDizzyBridge.apk"
TMP=/data/local/tmp/AntiDizzyBridge.apk

for i in $(seq 1 90); do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 1
done
sleep 3

# ---- 安装「卸载墓碑」 ----
# 模块被删除后，KernelSU 只会删 /data/adb/modules/motion_cues_port，
# 不管 APK、Settings、LSPosed 注入。所以把清理脚本放到模块目录之外的
# /data/adb/service.d/，下次开机会检查模块是否还在：
#   还在   → 什么都不做
#   不在了 → 清残留并自删
SD=/data/adb/service.d
if [ -d "$SD" ] || mkdir -p "$SD" 2>/dev/null; then
  cp -f "$MODDIR/cleanup.sh" "$SD/motion_cues_cleanup.sh" 2>/dev/null
  chmod 755 "$SD/motion_cues_cleanup.sh" 2>/dev/null
fi

if ! pm path "$PKG" >/dev/null 2>&1; then
  cp -f "$APK" "$TMP"
  chmod 644 "$TMP"
  chcon u:object_r:shell_data_file:s0 "$TMP" 2>/dev/null
  pm install -r -g "$TMP" >> "$LOG" 2>&1
  echo "bridge: installed — 请在 LSPosed 启用『AntiDizzy Bridge』并勾选『系统框架』后重启" >> "$LOG"
fi
