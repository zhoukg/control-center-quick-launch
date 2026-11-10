#!/usr/bin/env bash
# ControlCenterApp 手工构建脚本（无 Gradle）：aapt2 + javac + d8 + zipalign + apksigner
# 产物：dist/ControlCenter-<version>.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-C:/Users/zhou1y1001/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-37.0"
JAR="$PLATFORM/android.jar"
JDK_WIN='C:\Program Files\Java\jdk-21.0.12'
JDK_UNIX="/c/Program Files/Java/jdk-21.0.12"
JAVA_HOME="$JDK_WIN"
export JAVA_HOME
JAVAC="$JDK_UNIX/bin/javac.exe"
KEYTOOL="$JDK_UNIX/bin/keytool.exe"
PY="C:/Users/zhou1y1001/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"

APP="$ROOT/app/src/main"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
KEYS="$ROOT/keys"
VERSION="1.0"
VERSION_CODE="1"

for f in "$BT/aapt2.exe" "$JAR" "$JAVAC" "$PY"; do
  [ -e "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
done

rm -rf "$BUILD"
mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> [1/7] aapt2 compile"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$BUILD/compiled/res.zip"

echo "==> [2/7] aapt2 link"
"$BT/aapt2.exe" link \
  -o "$BUILD/app-unsigned.apk" \
  -I "$JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION" \
  --java "$BUILD/gen" \
  "$BUILD/compiled/res.zip"

echo "==> [3/7] javac"
JAVA_SOURCES="$(find "$APP/java" "$BUILD/gen" -name '*.java' | tr '\n' ' ')"
# shellcheck disable=SC2086
"$JAVAC" --release 17 -encoding UTF-8 -cp "$JAR" -d "$BUILD/classes" $JAVA_SOURCES

echo "==> [4/7] d8 (dex)"
CLASSES="$(find "$BUILD/classes" -name '*.class' | tr '\n' ' ')"
# shellcheck disable=SC2086
"$BT/d8.bat" --release --min-api 26 --lib "$JAR" --output "$BUILD/dex" $CLASSES

echo "==> [5/7] 合并 classes.dex"
"$PY" - "$BUILD/app-unsigned.apk" "$BUILD/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("classes.dex 已写入 APK")
PYEOF

echo "==> [6/7] zipalign"
"$BT/zipalign.exe" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "==> [7/7] apksigner 签名"
mkdir -p "$KEYS"
if [ ! -f "$KEYS/release.jks" ]; then
  echo "    生成签名密钥 keys/release.jks（口令 controlcenter，请勿外传）"
  "$KEYTOOL" -genkeypair -keystore "$KEYS/release.jks" -alias controlcenter \
    -storepass controlcenter -keypass controlcenter \
    -dname "CN=ControlCenter, OU=DSH, O=DSH, L=Dalian, ST=Liaoning, C=CN" \
    -keyalg RSA -keysize 2048 -validity 10000 -noprompt >/dev/null 2>&1
fi
OUT="$DIST/ControlCenter-$VERSION.apk"
"$BT/apksigner.bat" sign \
  --ks "$KEYS/release.jks" --ks-pass pass:controlcenter --key-pass pass:controlcenter \
  --out "$OUT" "$BUILD/app-aligned.apk"
"$BT/apksigner.bat" verify --print-certs "$OUT" | head -3

echo
echo "构建完成: $OUT"
ls -la "$OUT"
echo "==> 应用信息:"
"$BT/aapt2.exe" dump badging "$OUT" | sed -n '1,6p'
