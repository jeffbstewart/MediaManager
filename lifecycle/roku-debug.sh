#!/bin/bash
# Connects to the Roku debug console (BrightScript print output) via netcat.
# Output is both displayed and saved to roku-debug.log.
# Filter for [MM] lines with: grep '\[MM\]' roku-debug.log
#
# Reads connection info from secrets/roku-deploy.env.

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

LOG_FILE="${1:-data/roku-debug.log}"
mkdir -p "$(dirname "$LOG_FILE")"

echo "Connecting to Roku debug console at $ROKU_IP:8085..."
echo "Output will be saved to $LOG_FILE"
echo "Press Ctrl+C to disconnect."
echo "---"

# Use netcat to stream the debug console, tee to file
ncat "$ROKU_IP" 8085 | tee "$LOG_FILE"
