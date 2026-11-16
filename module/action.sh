#!/system/bin/sh
# KernelSU 管理器「执行」按钮：只读展示当前参数。
# 调节请用模块的 WebUI（管理器 → 模块 → 晕动舒缓显示 → WebUI）。

get() { v=$(settings --user 0 get secure "$1" 2>/dev/null); [ -z "$v" ] || [ "$v" = "null" ] && echo "$2" || echo "$v"; }

GAIN=$(get motion_cues_gain 1.0)
MODE=$(get oplus_customize_antidizzy_mode 2)
ENABLE=$(get oplus_customize_antidizzy_enable 1)
TEST=$(get motion_cues_bridge_test 0)

VRMS=$(get motion_cues_veh_rms 0.12)
VLOW=$(get motion_cues_veh_low 0.55)
VROT=$(get motion_cues_veh_rot 0.12)
VSTEP=$(get motion_cues_veh_step 0.45)
VHOLD=$(get motion_cues_veh_hold 8)

echo "━━ 晕动舒缓显示 · 当前参数 ━━━━━━"
[ "$ENABLE" = "1" ] && S1="✔ 已开启" || S1="✘ 已关闭"
case "$MODE" in
  1) S2="仅车辆运动时显示" ;;
  2) S2="常驻显示" ;;
  *) S2="未知($MODE)" ;;
esac
echo "  功能开关    : $S1"
echo "  显示模式    : $S2"
echo "  圆点灵敏度  : ${GAIN}×"
[ "$TEST" != "0" ] && echo "  ⚠ 测试模式  : $TEST（仍在运行，请关闭）"

echo ""
echo "━━ 车辆识别阈值 ━━━━━━"
echo "  振动能量下限 : ${VRMS:-0.12（默认）}"
echo "  低频占比下限 : ${VLOW:-0.55（默认）}"
echo "  陀螺上限     : ${VROT:-0.12（默认）}"
echo "  步态拒绝阈值 : ${VSTEP:-0.45（默认）}"
echo "  进入判定秒数 : ${VHOLD:-8（默认）}"

echo ""
echo "━━━ 修改方式 ━━━━━"
echo "  推荐用本模块的 WebUI（管理器 → 模块 → 晕动舒缓显示 → WebUI），"
echo "  灵敏度/模式/阈值均有界面控件。"
echo ""
echo "  命令行等价（adb 或终端模拟器）："
echo "    settings put secure motion_cues_gain 2"
echo "    settings put secure oplus_customize_antidizzy_mode 2"
echo ""
echo "  改动约 2 秒后生效，无需重启。"
