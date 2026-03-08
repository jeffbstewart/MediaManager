#!/bin/bash
# Stop the transcode buddy worker by finding and killing its Java process(es).

PIDS=$(wmic process where "name='java.exe'" get ProcessId,CommandLine 2>/dev/null \
    | grep "transcode-buddy" \
    | awk '{print $NF}' \
    | tr -d '\r')

if [ -z "$PIDS" ]; then
    echo "Transcode buddy is not running."
    exit 0
fi

for PID in $PIDS; do
    echo "Stopping transcode buddy (PID $PID)..."
    taskkill //PID "$PID" //F > /dev/null 2>&1
done
echo "Stopped."
