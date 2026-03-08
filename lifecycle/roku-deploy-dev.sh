#!/bin/bash
# Packages roku-channel/ into a zip and sideloads it to the dev Roku device.
# Reads connection info from secrets/roku-deploy-dev.env.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
cd "$PROJECT_ROOT"

# Load config
if [ ! -f secrets/roku-deploy-dev.env ]; then
    echo "Error: secrets/roku-deploy-dev.env not found. Fill in your values."
    exit 1
fi
source secrets/roku-deploy-dev.env

if [ -z "$ROKU_IP" ] || [ -z "$ROKU_DEV_PASSWORD" ]; then
    echo "Error: ROKU_IP and ROKU_DEV_PASSWORD must be set in secrets/roku-deploy-dev.env"
    exit 1
fi

# Package
rm -f roku-channel.zip
cd roku-channel
zip -r ../roku-channel.zip . -x '.*'
cd ..
echo "Packaged roku-channel.zip ($(du -h roku-channel.zip | cut -f1))"

# Sideload
echo "Deploying to dev Roku at $ROKU_IP..."
curl --user "rokudev:$ROKU_DEV_PASSWORD" \
     --digest \
     -F "mysubmit=Install" \
     -F "archive=@roku-channel.zip" \
     "http://$ROKU_IP/plugin_install"

echo ""
echo "Done! Channel should be launching on your dev Roku."
