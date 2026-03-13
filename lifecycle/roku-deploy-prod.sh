#!/bin/bash
# Packages roku-channel/ into a zip and sideloads it to the prod Roku device.
# Reads connection info from secrets/roku-deploy-prod.env.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
cd "$PROJECT_ROOT"

# Load config
if [ ! -f secrets/roku-deploy-prod.env ]; then
    echo "Error: secrets/roku-deploy-prod.env not found. Copy secrets/example.roku-deploy.env and fill in your values."
    exit 1
fi
source secrets/roku-deploy-prod.env

if [ -z "$ROKU_IP" ] || [ -z "$ROKU_DEV_PASSWORD" ]; then
    echo "Error: ROKU_IP and ROKU_DEV_PASSWORD must be set in secrets/roku-deploy-prod.env"
    exit 1
fi

# Stamp build timestamp into manifest
BUILD_TS=$(date +%Y%m%d-%H%M%S)
# Remove any existing build_timestamp line, then append
sed -i '/^build_timestamp=/d' roku-channel/manifest
echo "build_timestamp=$BUILD_TS" >> roku-channel/manifest
echo "Build timestamp: $BUILD_TS"

# Package
rm -f roku-channel.zip
cd roku-channel
zip -r ../roku-channel.zip . -x '.*'
cd ..

# Restore manifest (don't leave timestamp in source)
sed -i '/^build_timestamp=/d' roku-channel/manifest
echo "Packaged roku-channel.zip ($(du -h roku-channel.zip | cut -f1))"

# Sideload
echo "Deploying to PROD Roku at $ROKU_IP..."
curl --user "rokudev:$ROKU_DEV_PASSWORD" \
     --digest \
     -F "mysubmit=Install" \
     -F "archive=@roku-channel.zip" \
     "http://$ROKU_IP/plugin_install"

echo ""
echo "Done! Channel should be launching on your prod Roku."
