#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../android-tv"

SIGNING_PROPS="../secrets/android-tv-signing.properties"
if [ ! -f "$SIGNING_PROPS" ]; then
    echo "ERROR: $SIGNING_PROPS not found."
    echo "Copy from secrets/example.android-tv-signing.properties and fill in values."
    exit 1
fi

echo "=== Building signed Android TV release APK ==="
./gradlew assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
echo ""
echo "Release APK: $APK"
echo "Size: $(du -h "$APK" | cut -f1)"

# Optionally install to connected device
if [ "${1:-}" = "--install" ]; then
    echo "Installing to device..."
    adb install -r "$APK"
    adb shell am force-stop net.stewart.mediamanager.tv
    adb shell am start -n net.stewart.mediamanager.tv/.MainActivity
fi
