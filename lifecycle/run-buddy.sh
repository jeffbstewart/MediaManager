#!/usr/bin/env bash
# Run the transcode buddy worker
# Uses the buddy.properties config in transcode-buddy/
#
# Uses javaw.exe (no console window) so ffmpeg/whisper child processes
# cannot emit BEL characters to a console and cause audible beeps.
# SLF4J is configured to write logs to data/buddy.log directly
# (see simplelogger.properties), so javaw's lack of stdout/stderr is fine.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
INSTALL_DIR="$PROJECT_ROOT/transcode-buddy/build/install/transcode-buddy"

if [ ! -d "$INSTALL_DIR" ]; then
    echo "Buddy not installed. Building..."
    cd "$PROJECT_ROOT" && ./gradlew :transcode-buddy:installDist || exit 1
fi

export JAVA_HOME="$HOME/.jdks/corretto-25.0.2"
cd "$PROJECT_ROOT/transcode-buddy"

# Use relative lib path from CWD — Java on Windows/Git Bash does not
# understand POSIX-style absolute paths like /c/Programming/...
LIB_DIR="../transcode-buddy/build/install/transcode-buddy/lib"

# Build classpath from all jars in lib/
CLASSPATH=""
for jar in "$LIB_DIR"/*.jar; do
    if [ -n "$CLASSPATH" ]; then
        CLASSPATH="$CLASSPATH;$jar"
    else
        CLASSPATH="$jar"
    fi
done

# Ensure log directory exists
mkdir -p "$PROJECT_ROOT/data"

echo "Starting transcode buddy (logs: data/buddy.log)..."
"$JAVA_HOME/bin/javaw" -classpath "$CLASSPATH" net.stewart.transcodebuddy.MainKt &
BUDDY_PID=$!
echo "Transcode buddy started (PID $BUDDY_PID)."
