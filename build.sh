#!/bin/bash
# Builds build/tvodds.apk with the bare Android SDK (no Gradle, no dependencies).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
[ -d "$SDK/build-tools" ] && [ -d "$SDK/platforms" ] || { echo "Android SDK not found at $SDK (set ANDROID_HOME)"; exit 1; }
# JDK 17+: $JAVA_HOME, else macOS java_home, else common Homebrew locations.
if [ ! -x "${JAVA_HOME:-}/bin/javac" ]; then
  for c in "$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)" /opt/homebrew/opt/openjdk@21 \
           /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@21 /usr/local/opt/openjdk@17; do
    if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then export JAVA_HOME="$c"; break; fi
  done
fi
[ -x "${JAVA_HOME:-}/bin/javac" ] || { echo "JDK 17+ not found (brew install openjdk@21, or set JAVA_HOME)"; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -A assets -I "$JAR" \
  --min-sdk-version 31 --target-sdk-version 34 --version-code "${VERSION_CODE:-1}" --version-name 1.0
javac -nowarn -source 17 -target 17 -Xlint:-options -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvodds -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvodds" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out build/tvodds.apk build/aligned.apk
echo "built build/tvodds.apk"
