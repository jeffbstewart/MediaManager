#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../android-tv"

echo "=== Building and deploying Android TV debug APK ==="
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
DEVICE="${1:-}"

if [ -n "$DEVICE" ]; then
    adb -s "$DEVICE" install -r "$APK"
else
    adb install -r "$APK"
fi

echo ""
echo "Installed. Launching..."
adb shell am force-stop net.stewart.mediamanager.tv
adb shell am start -n net.stewart.mediamanager.tv/.MainActivity
