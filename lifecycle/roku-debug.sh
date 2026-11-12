#!/bin/bash
# Connects to the Roku debug console (BrightScript print output) via netcat.
# Output is both displayed and saved to roku-debug.log.
# Filter for [MM] lines with: grep '\[MM\]' roku-debug.log
#
# Usage:
#   ./roku-debug.sh                    # Connect to dev Roku
#   ./roku-debug.sh prod               # Connect to prod Roku
#   ./roku-debug.sh dev path/to/log    # Custom log file

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
cd "$PROJECT_ROOT"

# Determine target (dev or prod)
TARGET="${1:-dev}"
if [ "$TARGET" = "dev" ] || [ "$TARGET" = "prod" ]; then
    shift
else
    TARGET="dev"
fi

ENV_FILE="secrets/roku-deploy-${TARGET}.env"
if [ ! -f "$ENV_FILE" ]; then
    echo "Error: $ENV_FILE not found. Copy secrets/example.roku-deploy.env and fill in your values."
    exit 1
fi
source "$ENV_FILE"

if [ -z "$ROKU_IP" ]; then
    echo "Error: ROKU_IP must be set in $ENV_FILE"
    exit 1
fi

LOG_FILE="${1:-data/roku-debug.log}"
mkdir -p "$(dirname "$LOG_FILE")"

echo "Connecting to Roku debug console at $ROKU_IP:8085..."
echo "Output will be saved to $LOG_FILE"
echo "Press Ctrl+C to disconnect."
echo "---"

# Use netcat to stream the debug console, tee to file
ncat "$ROKU_IP" 8085 | tee "$LOG_FILE"
