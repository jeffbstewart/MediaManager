#!/bin/bash
# Screenshots of one window the operator picks (built for Apple's CarPlay
# Simulator), without granting Screen Recording permission to anything.
#
#   lifecycle/carplay-shot.sh start        build if needed, launch; macOS shows
#                                          its window picker: choose the
#                                          CarPlay Simulator window
#   lifecycle/carplay-shot.sh shot [NAME]  capture the chosen window to
#                                          data/screenshots/carplay/NAME.png
#   lifecycle/carplay-shot.sh stop         quit; the next start needs a new pick
#
# macOS gives the helper the chosen window only, for as long as it runs.
# Nothing is stored, and it needs no Screen Recording permission: refuse
# any prompt for one. Source: lifecycle/carplay-shot/.
#
# CarPlay Simulator ("Additional Tools for Xcode" > Hardware) shows the
# CarPlay screen of a physical iPhone connected by USB; this Xcode's iOS
# Simulator (DeviceHub) has no CarPlay display.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
SRC="$SCRIPT_DIR/carplay-shot"
APP="$ROOT/data/tools/CarPlayShot.app"
WORK="$ROOT/data/screenshots/carplay"
PIDFILE="$WORK/carplay-shot.pid"
LOG="$WORK/carplay-shot.log"

build() {
    if [ -x "$APP/Contents/MacOS/CarPlayShot" ] \
        && [ "$APP/Contents/MacOS/CarPlayShot" -nt "$SRC/CarPlayShot.swift" ] \
        && [ "$APP/Contents/Info.plist" -nt "$SRC/Info.plist" ]; then
        return
    fi
    echo "building CarPlayShot"
    mkdir -p "$APP/Contents/MacOS"
    cp "$SRC/Info.plist" "$APP/Contents/Info.plist"
    xcrun swiftc -O -o "$APP/Contents/MacOS/CarPlayShot" "$SRC/CarPlayShot.swift"
    codesign --force --sign - "$APP"
}

running_pid() {
    [ -f "$PIDFILE" ] || return 1
    local pid
    pid="$(cat "$PIDFILE")"
    kill -0 "$pid" 2>/dev/null || return 1
    echo "$pid"
}

case "${1:-}" in
    start)
        if pid="$(running_pid)"; then
            echo "already running (pid $pid)"
            exit 0
        fi
        build
        mkdir -p "$WORK"
        : > "$LOG"
        open -n "$APP" --args "$WORK"
        echo "started; choose the CarPlay Simulator window in the picker"
        ;;
    shot)
        pid="$(running_pid)" || { echo "not running; run: $0 start" >&2; exit 1; }
        name="${2:-carplay-$(date +%Y%m%d-%H%M%S)}"
        before="$(wc -l < "$LOG")"
        printf '%s\n' "$name" > "$WORK/request"
        kill -USR1 "$pid"
        for _ in $(seq 1 50); do
            line="$(tail -n +"$((before + 1))" "$LOG" | grep -E "saved |error:" | head -1 || true)"
            if [ -n "$line" ]; then
                echo "$line"
                case "$line" in *error:*) exit 1 ;; esac
                exit 0
            fi
            sleep 0.1
        done
        echo "no answer from the helper; see $LOG" >&2
        exit 1
        ;;
    stop)
        if pid="$(running_pid)"; then
            kill -TERM "$pid"
            echo "stopped"
        else
            echo "not running"
        fi
        ;;
    *)
        sed -n '2,13p' "$0"
        exit 2
        ;;
esac
