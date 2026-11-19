#!/bin/bash
# Text contrast check for the CarPlay screen (WCAG AA, 4.5:1 by default).
#
#   lifecycle/carplay-contrast.sh [NAME]          take a screenshot with
#                                                 carplay-shot.sh and check it
#   lifecycle/carplay-contrast.sh --file PNG      check an existing screenshot
#
# Options, before NAME or --file:
#   --rect "X0 Y0 X1 Y1"   area to check, in screenshot pixels. Default: the
#                          car screen of a CarPlay Simulator window at its
#                          standard size (1520x1416); other sizes need --rect.
#   --min RATIO            minimum contrast (default 4.5)
#
# Exits 1 when any line of text is below the minimum, or none is found.
# Needs lifecycle/carplay-shot.sh running on the CarPlay Simulator window
# (except with --file). Prints every line of text it read and its contrast.
# Manual: it needs the user's phone attached, so it is not part of CI.
# Source: lifecycle/carplay-contrast/.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
SRC="$SCRIPT_DIR/carplay-contrast/Contrast.swift"
TOOL="$ROOT/data/tools/carplay-contrast"
WORK="$ROOT/data/screenshots/carplay"

rect=""
min="4.5"
file=""
while [ $# -gt 0 ]; do
    case "$1" in
        --rect) rect="$2"; shift 2 ;;
        --min) min="$2"; shift 2 ;;
        --file) file="$2"; shift 2 ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) break ;;
    esac
done

if [ ! -x "$TOOL" ] || [ "$SRC" -nt "$TOOL" ]; then
    echo "building carplay-contrast"
    mkdir -p "$(dirname "$TOOL")"
    xcrun swiftc -O -o "$TOOL" "$SRC"
fi

if [ -z "$file" ]; then
    name="${1:-contrast-$(date +%Y%m%d-%H%M%S)}"
    "$SCRIPT_DIR/carplay-shot.sh" shot "$name"
    file="$WORK/$name.png"
fi

if [ -z "$rect" ]; then
    size="$(sips -g pixelWidth -g pixelHeight "$file" | awk '/pixel/ {print $2}' | paste -sd x -)"
    if [ "$size" != "1520x1416" ]; then
        echo "screenshot is $size, not the standard 1520x1416 window; pass --rect" >&2
        exit 2
    fi
    rect="20 124 1500 1012"
fi

# shellcheck disable=SC2086 # rect is four numbers, split on purpose
"$TOOL" "$file" $rect --min "$min"
