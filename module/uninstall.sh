#!/system/bin/sh
# KernelSU 在用户删除本模块时执行（模块更新时不会执行）。
# 职责：把 APK、Settings、临时文件、bind mount 一并带走。
MODDIR=${0%/*}
PKG=com.workbuddy.antidizzybridge

if [ -f "$MODDIR/cleanup.sh" ]; then
  sh "$MODDIR/cleanup.sh" --force
  exit 0
fi

# 兜底：极端情况下 cleanup.sh 已不在（目录被提前清掉），
# 至少把最关键的两件事做掉——停注入、卸挂载。
pm uninstall "$PKG" >/dev/null 2>&1
for f in /system_ext/etc/permissions/com.oplus.features_config.xml \
         /my_product/etc/permissions/com.oplus.features_config.xml \
         /my_product/etc/extension/com.oplus.oplus-feature.xml; do
  umount "$f" 2>/dev/null
done
rm -f /data/local/tmp/motion_cues_port.log /data/local/tmp/AntiDizzyBridge.apk

exit 0
