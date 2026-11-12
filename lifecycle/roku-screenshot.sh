#!/bin/bash
# Capture a screenshot from the Roku via the OBS Virtual Camera.
#
# Prerequisites:
#   - HDMI capture card connected to the Roku
#   - OBS running with the capture card as a source and Virtual Camera started
#
# Usage:
#   ./lifecycle/roku-screenshot.sh                          # saves to data/screenshots/roku-capture.png
#   ./lifecycle/roku-screenshot.sh data/screenshots/foo.png # saves to custom path

OUTPUT="${1:-data/screenshots/roku-capture.png}"
DEVICE="OBS Virtual Camera"

mkdir -p "$(dirname "$OUTPUT")"

ffmpeg -f dshow -i video="$DEVICE" -frames:v 1 -update 1 -y "$OUTPUT" 2>/dev/null

if [ -f "$OUTPUT" ]; then
    echo "Saved: $OUTPUT"
else
    echo "ERROR: Screenshot failed. Is OBS Virtual Camera running?" >&2
    exit 1
fi
