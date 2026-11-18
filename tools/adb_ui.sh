#!/bin/bash
# 真机自动化辅助。解决"通知栏挡住 + 屏幕熄灭 + 横竖屏坐标变化"这三个反复踩的坑。
#
# 用法：
#   bash tools/adb_ui.sh wake          # 唤醒 + 解锁 + 收通知栏 + 拉起 App
#   bash tools/adb_ui.sh tap X Y       # 点击
#   bash tools/adb_ui.sh shot 名字      # 截图并拉到 验证/ 目录
#   bash tools/adb_ui.sh size          # 打印当前屏幕尺寸/方向
set -u

export TMP="E:/ai/deepseek/tjdaka/.tmp"
ADB="C:/tbapp/toolchain/android-sdk/platform-tools/adb.exe"
PKG="com.daka.app"
ACT="$PKG/.MainActivity"
SHOT_DIR="E:/ai/deepseek/地图打卡照相APP-需求与方案/新工程/验证"

a() { timeout 45 "$ADB" "$@"; }

case "${1:-}" in
  wake)
    a shell input keyevent KEYCODE_WAKEUP
    sleep 0.5
    a shell wm dismiss-keyguard
    sleep 0.5
    # 收通知栏：HOME 再重新拉起最可靠（swipe/cmd statusbar 在 MIUI 上时好时坏）
    a shell input keyevent KEYCODE_HOME
    sleep 1
    a shell am start -n "$ACT" >/dev/null 2>&1
    sleep "${2:-8}"
    a shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus" | head -1
    ;;
  tap)
    a shell input tap "$2" "$3"
    echo "tap $2 $3"
    ;;
  shot)
    NAME="${2:-shot}"
    mkdir -p "$SHOT_DIR"
    a shell screencap -p "/sdcard/_ui_shot.png"
    a pull "/sdcard/_ui_shot.png" "$SHOT_DIR/$NAME.png" 2>&1 | tail -1
    ;;
  size)
    a shell wm size
    a shell wm density
    a shell dumpsys window 2>/dev/null | grep -oE "mRotation=[0-9]+" | head -1
    ;;
  *)
    echo "用法: bash tools/adb_ui.sh {wake [秒]|tap X Y|shot 名字|size}"
    ;;
esac
