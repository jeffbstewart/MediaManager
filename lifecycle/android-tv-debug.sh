#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$SCRIPT_DIR/../data"
LOG_FILE="${1:-$LOG_DIR/android-tv-debug.log}"

mkdir -p "$(dirname "$LOG_FILE")"

echo "=== Streaming Android TV logcat to $LOG_FILE ==="
echo "Filter: MediaManager, GrpcClient, ExoPlayer"
echo "Press Ctrl+C to stop."
echo ""

adb logcat -c
adb logcat \
    net.stewart.mediamanager.tv:V \
    GrpcClient:V \
    ExoPlayer:W \
    AndroidRuntime:E \
    *:S \
    | tee "$LOG_FILE"
