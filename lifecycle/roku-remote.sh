#!/bin/bash
# Sends commands to the Roku via the External Control Protocol (ECP).
# ECP is an HTTP REST API on port 8060 that controls the Roku remotely.
#
# Usage:
#   ./roku-remote.sh                    # Press Select (OK)
#   ./roku-remote.sh <key>              # Press a key (e.g. Home, Back, Up, Down, Left, Right, Select)
#   ./roku-remote.sh launch             # Launch the sideloaded dev channel
#   ./roku-remote.sh apps               # List installed apps
#   ./roku-remote.sh active             # Show currently active app
#   ./roku-remote.sh info               # Show device info
#   ./roku-remote.sh player             # Show media player state
#   ./roku-remote.sh type <text>        # Type text into a Roku keyboard dialog
#
# Key names: Home, Rev, Fwd, Play, Select, Left, Right, Down, Up, Back,
#            InstantReplay, Info, Backspace, Search, Enter,
#            VolumeDown, VolumeUp, VolumeMute
#
# Reads ROKU_IP from secrets/roku-deploy.env.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
cd "$PROJECT_ROOT"

# Load config
if [ ! -f secrets/roku-deploy.env ]; then
    echo "Error: secrets/roku-deploy.env not found. Copy secrets/example.roku-deploy.env and fill in your values."
    exit 1
fi
source secrets/roku-deploy.env

if [ -z "$ROKU_IP" ]; then
    echo "Error: ROKU_IP must be set in secrets/roku-deploy.env"
    exit 1
fi

BASE_URL="http://$ROKU_IP:8060"

ACTION="${1:-Select}"

case "$ACTION" in
    launch)
        echo "Launching dev channel on $ROKU_IP..."
        curl -s -d '' "$BASE_URL/launch/dev"
        echo "Done."
        ;;
    apps)
        echo "Installed apps on $ROKU_IP:"
        curl -s "$BASE_URL/query/apps"
        echo ""
        ;;
    active)
        echo "Active app on $ROKU_IP:"
        curl -s "$BASE_URL/query/active-app"
        echo ""
        ;;
    info)
        echo "Device info for $ROKU_IP:"
        curl -s "$BASE_URL/query/device-info"
        echo ""
        ;;
    player)
        echo "Media player state on $ROKU_IP:"
        curl -s "$BASE_URL/query/media-player"
        echo ""
        ;;
    type)
        TEXT="${2:-}"
        if [ -z "$TEXT" ]; then
            echo "Usage: $0 type <text>"
            exit 1
        fi
        echo "Typing '$TEXT' on $ROKU_IP..."
        for (( i=0; i<${#TEXT}; i++ )); do
            CHAR="${TEXT:$i:1}"
            # URL-encode special characters for ECP Lit_ endpoint
            ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$CHAR', safe=''))" 2>/dev/null || echo "$CHAR")
            curl -s -d '' "$BASE_URL/keypress/Lit_${ENCODED}"
        done
        echo "Done."
        ;;
    *)
        # Treat as a key press
        echo "Pressing $ACTION on $ROKU_IP..."
        curl -s -d '' "$BASE_URL/keypress/$ACTION"
        echo "Done."
        ;;
esac
