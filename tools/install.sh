#!/usr/bin/env bash
# 一键安装 + 授权 + 开启无障碍 + 启动 ControlCenter
# 注意：覆盖安装后 Android 可能清理该 app 的无障碍服务状态，因此这里会重试并轮询确认已绑定。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-C:/Users/zhou1y1001/AppData/Local/Android/Sdk}"
ADB="$SDK/platform-tools/adb.exe"
PKG="com.dsh.controlcenter"
SVC="$PKG/$PKG.CCAccessibilityService"
APK="${1:-$ROOT/dist/ControlCenter-1.0.apk}"

[ -f "$APK" ] || { echo "APK 不存在: $APK（先运行 build.sh）" >&2; exit 1; }

echo "==> 1. 安装 APK"
"$ADB" install -r "$APK" || exit 1
sleep 2

echo "==> 2. 运行时权限（可失败，不影响核心功能）"
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

echo "==> 3. 开启无障碍服务（保留已有服务，仅追加本 app）"
enable_a11y() {
  local cur
  cur="$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')"
  case "$cur" in
    ""|"null") NEW="$SVC" ;;
    *"$PKG"*) NEW="$cur" ;;
    *) NEW="$cur:$SVC" ;;
  esac
  "$ADB" shell settings put secure enabled_accessibility_services "$NEW" >/dev/null 2>&1
  "$ADB" shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
}
bound() {
  local cur en
  cur="$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')"
  en="$("$ADB" shell settings get secure accessibility_enabled | tr -d '\r')"
  echo "$cur" | grep -q "$PKG" && [ "$en" = "1" ]
}
enable_a11y
for i in 1 2 3 4 5; do
  sleep 1
  if bound; then
    echo "    第 $i 次检查：无障碍服务已绑定 ✓"
    break
  fi
  echo "    第 $i 次检查：尚未绑定，重试..."
  enable_a11y
done
echo -n "    enabled_accessibility_services = "
"$ADB" shell settings get secure enabled_accessibility_services
echo -n "    accessibility_enabled = "
"$ADB" shell settings get secure accessibility_enabled
if bound; then
  echo "    Bound: $("$ADB" shell dumpsys accessibility | grep 'Bound services' | head -1 | cut -c1-80)"
else
  echo "    ⚠ 无障碍服务仍未绑定；可手动到 设置→无障碍→已下载的应用 中开启" >&2
fi

echo "==> 4. 启动 App（默认「打开即自动唤出控制中心」）"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 3
echo "==> 5. 当前窗口焦点（应出现 NotificationShade 表示控制中心已展开）"
"$ADB" shell dumpsys window | grep -E "mCurrentFocus" | head -1
