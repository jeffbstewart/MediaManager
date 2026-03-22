#!/bin/bash
# Deploy the iOS app to a connected device.
#
# Device identifier is read from secrets/ios-device.agent_visible_env
# to keep personal device info out of source control.
#
# Usage: ./lifecycle/ios-deploy.sh
#
# Setup:
#   cp secrets/example.ios-device.env secrets/ios-device.agent_visible_env
#   # Edit and set IOS_DEVICE_ID (find it with: xcrun devicectl list devices)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

# Load device config
DEVICE_ENV="$PROJECT_ROOT/secrets/ios-device.agent_visible_env"
if [ ! -f "$DEVICE_ENV" ]; then
    echo "ERROR: $DEVICE_ENV not found."
    echo "  cp secrets/example.ios-device.env secrets/ios-device.agent_visible_env"
    echo "  Then set IOS_DEVICE_ID (find it with: xcrun devicectl list devices)"
    exit 1
fi

while IFS='=' read -r key value; do
    key="${key//$'\r'/}"
    value="${value//$'\r'/}"
    [[ -z "$key" || "$key" =~ ^# ]] && continue
    export "$key"="$value"
done < "$DEVICE_ENV"

if [ -z "${IOS_DEVICE_ID:-}" ]; then
    echo "ERROR: IOS_DEVICE_ID not set in $DEVICE_ENV"
    exit 1
fi

PROJECT="$PROJECT_ROOT/ios/MediaManager/MediaManager.xcodeproj"

echo "=== Building MediaManager for device ==="
"$SCRIPT_DIR/ios-build.sh" --device "$IOS_DEVICE_ID"

echo ""
echo "=== Installing on device $IOS_DEVICE_ID ==="
BUILD_DIR=$(xcodebuild -project "$PROJECT" -scheme MediaManager -destination "platform=iOS,id=$IOS_DEVICE_ID" -showBuildSettings 2>/dev/null | grep " BUILT_PRODUCTS_DIR" | awk '{print $3}')
APP_PATH="$BUILD_DIR/MediaManager.app"

if [ ! -d "$APP_PATH" ]; then
    echo "ERROR: Built app not found at $APP_PATH"
    exit 1
fi

xcrun devicectl device install app --device "$IOS_DEVICE_ID" "$APP_PATH"

echo ""
echo "=== Launching ==="
xcrun devicectl device process launch --device "$IOS_DEVICE_ID" net.stewart.mediamanager

echo ""
echo "Done!"
