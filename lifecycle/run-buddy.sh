#!/usr/bin/env bash
# Run the transcode buddy worker.
# Cross-platform: works on macOS, Linux, and Windows (Git Bash).
#
# Logs are written to data/buddy.log via SLF4J simplelogger.properties.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
INSTALL_DIR="$PROJECT_ROOT/transcode-buddy/build/install/transcode-buddy"

if [ ! -d "$INSTALL_DIR" ]; then
    echo "Buddy not installed. Building..."
    cd "$PROJECT_ROOT" && ./gradlew :transcode-buddy:installDist || exit 1
fi

cd "$PROJECT_ROOT/transcode-buddy"

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

echo "Starting transcode buddy (logs: data/buddy.log)..."
echo "  JAVA_HOME: $JAVA_HOME"
echo "  Java bin:  $JAVA_BIN"
"$JAVA_BIN" -classpath "$CLASSPATH" net.stewart.transcodebuddy.MainKt &
BUDDY_PID=$!
echo "Transcode buddy started (PID $BUDDY_PID)."
