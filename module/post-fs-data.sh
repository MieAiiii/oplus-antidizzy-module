#!/system/bin/sh
# 自动为欧加(ColorOS)机型补上 AntiDizzy 特性开关。
# 不同机型/版本把特性声明放在不同文件里，这里逐个探测，谁存在就补谁。
MODDIR=${0%/*}
LOG=/data/local/tmp/motion_cues_port.log
: > "$LOG"
FEATURE=oplus.hardware.display.motion_sickness

CANDS="/system_ext/etc/permissions/com.oplus.features_config.xml
/system_ext/etc/permissions/com.oplus.features_ext.xml
/system_ext/etc/permissions/com.oplus.features.xml
/my_product/etc/permissions/com.oplus.features_config.xml
/my_product/etc/extension/com.oplus.oplus-feature.xml
/system_ext/etc/extension/com.oplus.oplus-feature.xml
/product/etc/extension/com.oplus.oplus-feature.xml"

i=0
found=0
for TGT in $CANDS; do
  [ -f "$TGT" ] || continue
  found=$((found+1))
  if grep -q "$FEATURE" "$TGT" 2>/dev/null; then
    echo "already: $TGT" >> "$LOG"
    continue
  fi
  i=$((i+1))
  TMP=/data/local/tmp/mc_feat_$i.xml
  # 在 </oplus-config> 之前插入一行（无该标签则追加到末尾前的最后一行）
  awk -v feat="$FEATURE" '
    /<\/oplus-config>/ { printf "\t<oplus-feature name=\"%s\"/>\n", feat }
    { print }
  ' "$TGT" > "$TMP" 2>/dev/null
  if ! grep -q "$FEATURE" "$TMP" 2>/dev/null; then
    awk -v feat="$FEATURE" '{ lines[NR]=$0 } END {
      for (n=1; n<=NR; n++) {
        if (n==NR && lines[n] ~ /^[[:space:]]*$/) continue
        if (n==NR) printf "\t<oplus-feature name=\"%s\"/>\n", feat
        print lines[n]
      }
    }' "$TGT" > "$TMP" 2>/dev/null
  fi
  if ! grep -q "$FEATURE" "$TMP" 2>/dev/null; then
    echo "inject-failed: $TGT" >> "$LOG"
    rm -f "$TMP"
    continue
  fi
  chmod --reference "$TGT" "$TMP" 2>/dev/null
  chown --reference "$TGT" "$TMP" 2>/dev/null
  chcon --reference "$TGT" "$TMP" 2>/dev/null
  if mount --bind "$TMP" "$TGT"; then
    echo "patched: $TGT" >> "$LOG"
  else
    echo "mount-failed: $TGT" >> "$LOG"
  fi
done

[ "$found" = "0" ] && echo "no candidate config found (unsupported rom?)" >> "$LOG"
echo "model=$(getprop ro.product.name) rom=$(getprop ro.build.version.oplusrom) done" >> "$LOG"
