#!/bin/bash
# Tail the transcode buddy log file.
# Usage: lifecycle/buddy-log.sh        # last 50 lines
#        lifecycle/buddy-log.sh -f      # live follow
#        lifecycle/buddy-log.sh 100     # last 100 lines

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$SCRIPT_DIR/../data/buddy.log"

if [ ! -f "$LOG_FILE" ]; then
    echo "No buddy log found at $LOG_FILE"
    exit 1
fi

if [ "$1" = "-f" ]; then
    tail -f "$LOG_FILE"
elif [ -n "$1" ]; then
    tail -n "$1" "$LOG_FILE"
else
    tail -n 50 "$LOG_FILE"
fi
