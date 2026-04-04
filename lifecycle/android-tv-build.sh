#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../android-tv"
echo "=== Building Android TV debug APK ==="
./gradlew assembleDebug
echo ""
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
