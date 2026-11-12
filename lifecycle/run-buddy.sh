#!/usr/bin/env bash
# Run the transcode buddy worker.
# Cross-platform: works on macOS, Linux, and Windows (Git Bash).
#
# Usage: run-buddy.sh [config-file]
#   config-file is resolved relative to transcode-buddy/.
#   Defaults to "buddy.properties".
#
# Logs are written to data/buddy.log via SLF4J simplelogger.properties.
#
# Only one buddy may run at a time — the production buddy and the demo
# buddy share a NAS, status_port, and ffmpeg binary, so a second start
# is rejected with a hint to stop the running one first.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
INSTALL_DIR="$PROJECT_ROOT/transcode-buddy/build/install/transcode-buddy"

CONFIG_FILE="${1:-buddy.properties}"

# Refuse to start if another buddy is already running. Matches the
# pattern stop-buddy.sh uses, so the two stay in sync.
case "$OSTYPE" in
    msys*|cygwin*|win32*)
        EXISTING=$(wmic process where "name='java.exe' or name='javaw.exe'" get ProcessId,CommandLine 2>/dev/null \
            | grep "transcode-buddy" \
            | awk '{print $NF}' \
            | tr -d '\r')
        ;;
    *)
        EXISTING=$(pgrep -f "transcode-buddy" 2>/dev/null)
        ;;
esac
if [ -n "$EXISTING" ]; then
    echo "ERROR: a transcode buddy is already running (PID(s): $(echo $EXISTING | tr '\n' ' '))."
    echo "Stop it first with: ./lifecycle/stop-buddy.sh"
    exit 1
fi

if [ ! -d "$INSTALL_DIR" ]; then
    echo "Buddy not installed. Building..."
    cd "$PROJECT_ROOT" && ./gradlew :transcode-buddy:installDist || exit 1
fi

cd "$PROJECT_ROOT/transcode-buddy"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: config file not found: transcode-buddy/$CONFIG_FILE"
    exit 1
fi

# Detect JAVA_HOME if not set
if [ -z "$JAVA_HOME" ]; then
    if [ -d "$HOME/.jdks/corretto-25.0.2" ]; then
        export JAVA_HOME="$HOME/.jdks/corretto-25.0.2"
    elif command -v java &>/dev/null; then
        export JAVA_HOME="$(java -XshowSettings:properties 2>&1 | grep 'java.home' | awk '{print $NF}')"
    else
        echo "ERROR: JAVA_HOME not set and java not found in PATH"
        exit 1
    fi
fi

# Use relative lib path from CWD
LIB_DIR="../transcode-buddy/build/install/transcode-buddy/lib"

# Detect OS for platform-specific settings
case "$OSTYPE" in
    darwin*|linux*)
        CLASSPATH_SEP=":"
        JAVA_BIN="$JAVA_HOME/bin/java"
        ;;
    msys*|cygwin*|win32*)
        CLASSPATH_SEP=";"
        # Use javaw on Windows (no console window, avoids FFmpeg BEL beeps)
        JAVA_BIN="$JAVA_HOME/bin/javaw"
        ;;
    *)
        CLASSPATH_SEP=":"
        JAVA_BIN="$JAVA_HOME/bin/java"
        ;;
esac

# Build classpath from all jars in lib/
CLASSPATH=""
for jar in "$LIB_DIR"/*.jar; do
    if [ -n "$CLASSPATH" ]; then
        CLASSPATH="$CLASSPATH$CLASSPATH_SEP$jar"
    else
        CLASSPATH="$jar"
    fi
done

# Ensure log directory exists
mkdir -p "$PROJECT_ROOT/data"

echo "Starting transcode buddy (config: $CONFIG_FILE, logs: data/buddy.log)..."
echo "  JAVA_HOME: $JAVA_HOME"
echo "  Java bin:  $JAVA_BIN"
"$JAVA_BIN" -classpath "$CLASSPATH" net.stewart.transcodebuddy.MainKt "$CONFIG_FILE" &
BUDDY_PID=$!
echo "Transcode buddy started (PID $BUDDY_PID)."
