#!/bin/bash
# Stop the Gradle dev server by finding and killing its Java process(es).
# This targets only the mediaManager Gradle run task, leaving the transcode buddy alone.

PIDS=$(wmic process where "name='java.exe'" get ProcessId,CommandLine 2>/dev/null \
    | grep -i "mediamanager" \
    | grep -v "transcode-buddy" \
    | awk '{print $NF}' \
    | tr -d '\r')

if [ -z "$PIDS" ]; then
    echo "Dev server is not running."
    exit 0
fi

for PID in $PIDS; do
    echo "Stopping dev server (PID $PID)..."
    taskkill //PID "$PID" //F > /dev/null 2>&1
done
echo "Stopped."
