#!/bin/bash
# Wrapper to invoke faster-whisper via Python on macOS.
# The transcode buddy calls this as: faster-whisper-mac.sh input.mkv --model X ...
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec /Applications/Xcode.app/Contents/Developer/usr/bin/python3 "$SCRIPT_DIR/faster-whisper-wrapper.py" "$@"
