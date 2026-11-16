#!/system/bin/sh
# ============================================================
# motion_cues_port 清理逻辑
#
# 两种调用方式：
#   1) 模块内 uninstall.sh 调用（--force）：用户删除模块时立即清理
#   2) 作为「墓碑」被开机执行（/data/adb/service.d/motion_cues_cleanup.sh）：
#      模块还在 → 什么都不做；模块已删 → 清理，成功后自删
#
# 存在的意义：KernelSU 删模块只会删 /data/adb/modules/motion_cues_port，
# 不会管 APK、Settings、临时文件，更不会停 LSPosed 注入。
# 这个脚本把那些一起带走。
# ============================================================

PKG=com.workbuddy.antidizzybridge
OLD=com.workbuddy.motioncues
MODDIR=/data/adb/modules/motion_cues_port

FORCE=0
[ "$1" = "--force" ] && FORCE=1

if [ "$FORCE" = "0" ]; then
  # 模块仍然存在 → 说明只是普通开机，不是卸载，直接退出
  [ -f "$MODDIR/module.prop" ] && exit 0
  # 模块没了，等系统起来再动手（pm / settings 依赖 system_server）
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do
    sleep 1
    i=$((i + 1))
  done
  sleep 5
fi

# ---- 1. 停注入：卸载 Xposed 桥接 APK ----
# 这一步最关键。LSPosed 的注入独立于 KernelSU 模块，
# 不卸 APK 的话即使模块删了，功能仍然在跑。
if pm path "$PKG" >/dev/null 2>&1; then
  pm uninstall "$PKG" >/dev/null 2>&1
fi
pm uninstall "$OLD" >/dev/null 2>&1

# ---- 2. 清掉写入的 Settings 键 ----
for k in oplus_customize_antidizzy_enable \
         oplus_customize_antidizzy_mode \
         oplus_customize_antidizzy_force_off \
         motion_cues_gain \
         motion_cues_bridge_test \
         motion_cues_veh_rms \
         motion_cues_veh_stop \
         motion_cues_veh_hold \
         motion_cues_veh_low \
         motion_cues_veh_rot \
         motion_cues_veh_step; do
  settings delete secure "$k" >/dev/null 2>&1
done

# ---- 3. 立即卸掉 bind mount，本轮就恢复原厂文件 ----
# 分区文件本身从未被修改（只读 EROFS + bind mount 覆盖），
# umount 之后看到的就是原文件，不需要等重启。
for f in /system_ext/etc/permissions/com.oplus.features_config.xml \
         /system_ext/etc/permissions/com.oplus.features_ext.xml \
         /system_ext/etc/permissions/com.oplus.features.xml \
         /my_product/etc/permissions/com.oplus.features_config.xml \
         /my_product/etc/extension/com.oplus.oplus-feature.xml \
         /system_ext/etc/extension/com.oplus.oplus-feature.xml \
         /product/etc/extension/com.oplus.oplus-feature.xml; do
  umount "$f" 2>/dev/null
done

# ---- 4. 清临时文件 ----
rm -f /data/local/tmp/motion_cues_port.log
rm -f /data/local/tmp/AntiDizzyBridge.apk
rm -f /data/local/tmp/MotionCues.apk
rm -f /data/local/tmp/mc_feat_*.xml

# ---- 5. 墓碑自毁 ----
# 只有确认 APK 已经不在了才删自己，否则留着下次开机再试。
if [ "$FORCE" = "0" ]; then
  if pm path "$PKG" >/dev/null 2>&1; then
    exit 0
  fi
  rm -f "$0"
fi

exit 0
