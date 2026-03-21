#!/bin/bash
# Stop the transcode buddy worker by finding and killing its Java process(es).
# Cross-platform: works on macOS, Linux, and Windows (Git Bash).

case "$OSTYPE" in
    msys*|cygwin*|win32*)
        PIDS=$(wmic process where "name='java.exe' or name='javaw.exe'" get ProcessId,CommandLine 2>/dev/null \
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
        ;;

    *)
        PIDS=$(pgrep -f "transcode-buddy" 2>/dev/null)

        if [ -z "$PIDS" ]; then
            echo "Transcode buddy is not running."
            exit 0
        fi

        for PID in $PIDS; do
            echo "Stopping transcode buddy (PID $PID)..."
            kill "$PID" 2>/dev/null
        done

        # Wait briefly, then force kill if still running
        sleep 2
        for PID in $PIDS; do
            if kill -0 "$PID" 2>/dev/null; then
                echo "Force killing PID $PID..."
                kill -9 "$PID" 2>/dev/null
            fi
        done
        ;;
esac

echo "Stopped."
