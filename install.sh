#!/bin/bash
# Installs the overlay on the connected Chromecast (adb over wireless debugging), grants the two
# permissions Google TV hides from its settings UI, and starts it.
set -euo pipefail
cd "$(dirname "$0")"
adb install -r build/tvodds.apk
adb shell appops set com.tvodds SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener com.tvodds/com.tvodds.MediaListener

adb shell am start -n com.tvodds/.ToggleActivity  # fresh install = stopped, so this turns it on
echo "installed; logs: adb logcat -s tvodds"
