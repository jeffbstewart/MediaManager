#!/usr/bin/env bash
# Stop the Angular dev server (ng serve on port 4200).

PORT=4200

# Find PID listening on the port
PID=$(netstat -ano 2>/dev/null | grep ":${PORT} .*LISTENING" | awk '{print $NF}' | head -1)

if [ -n "$PID" ] && [ "$PID" != "0" ]; then
    taskkill //F //PID "$PID" > /dev/null 2>&1 \
        || kill "$PID" 2>/dev/null
    echo "Angular dev server stopped (PID $PID)."
else
    echo "Angular dev server is not running."
fi
