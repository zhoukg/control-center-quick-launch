#!/usr/bin/env bash
# 真机验证：自动唤起 / 广播触发 / 崩溃日志 / 截图
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-C:/Users/zhou1y1001/AppData/Local/Android/Sdk}"
ADB="$SDK/platform-tools/adb.exe"
PKG="com.dsh.controlcenter"
D="$ROOT/tools/screenshots"
mkdir -p "$D"

focus() { "$ADB" shell dumpsys window | grep -E "mCurrentFocus" | head -1; }
shot()  { "$ADB" exec-out screencap -p > "$D/$1"; }
collapse() { "$ADB" shell cmd statusbar collapse >/dev/null 2>&1; sleep 0.6; }

echo "== 0. 准备：收起面板、回桌面"
collapse
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell wm dismiss-keyguard
sleep 0.5
echo "   focus(before) = $(focus)"

echo "== 1. 启动 App（自动唤起模式）"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
echo "   focus(after launch) = $(focus)"
shot 10-auto-launch.png

echo "== 2. 广播触发（等效点击按钮 / 外部触发）"
collapse
"$ADB" shell am broadcast -a com.dsh.controlcenter.ACTION_SUMMON -p "$PKG" >/dev/null 2>&1
sleep 2
echo "   focus(after broadcast) = $(focus)"
shot 11-broadcast.png

echo "== 3. 最近崩溃日志"
"$ADB" logcat -d -b crash 2>/dev/null | grep -iE "controlcenter|FATAL" | tail -10 || true
echo "== 4. 进程与无障碍服务状态"
"$ADB" shell dumpsys accessibility 2>/dev/null | grep -iE "controlcenter|enabled services|bound services" | head -10 || true

echo "== 完成，截图目录: $D"
ls -la "$D" | tail -6
