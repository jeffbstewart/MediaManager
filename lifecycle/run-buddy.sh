#!/usr/bin/env bash
# Run the transcode buddy worker
# Uses the buddy.properties config in transcode-buddy/

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
INSTALL_DIR="$PROJECT_ROOT/transcode-buddy/build/install/transcode-buddy"

if [ ! -d "$INSTALL_DIR" ]; then
    echo "Buddy not installed. Building..."
    cd "$PROJECT_ROOT" && ./gradlew :transcode-buddy:installDist || exit 1
fi

export JAVA_HOME="$HOME/.jdks/corretto-25.0.2"
cd "$PROJECT_ROOT/transcode-buddy"
echo "Starting transcode buddy..."
"$INSTALL_DIR/bin/transcode-buddy"
