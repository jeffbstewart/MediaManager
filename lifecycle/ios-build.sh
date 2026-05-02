#!/bin/bash
# Build the iOS app.
#
# Handles the two-pass build required for proto code generation:
# Pass 1 generates .pb.swift and .grpc.swift files from proto/*.proto
# Pass 2 compiles them alongside the rest of the app.
#
# Usage:
#   ./lifecycle/ios-build.sh                    # Build for generic iOS device
#   ./lifecycle/ios-build.sh --simulator        # Build for iOS Simulator
#   ./lifecycle/ios-build.sh --device <ID>      # Build for specific device
#   ./lifecycle/ios-build.sh --release          # Release configuration
#
# Prerequisites:
#   brew install protobuf swift-protobuf protoc-gen-grpc-swift

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
PROJECT="$PROJECT_ROOT/ios/MediaManager/MediaManager.xcodeproj"

# Parse arguments
DESTINATION="generic/platform=iOS"
CONFIGURATION="Debug"
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --simulator)
            DESTINATION="generic/platform=iOS Simulator"
            shift
            ;;
        --device)
            DESTINATION="platform=iOS,id=$2"
            shift 2
            ;;
        --release)
            CONFIGURATION="Release"
            shift
            ;;
        *)
            EXTRA_ARGS+=("$1")
            shift
            ;;
    esac
done

# Verify configuration files exist
XCCONFIG="$PROJECT_ROOT/ios/MediaManager/Developer.xcconfig"
BRANDING="$PROJECT_ROOT/ios/MediaManager/Branding.xcconfig"
if [ ! -f "$XCCONFIG" ]; then
    echo "ERROR: $XCCONFIG not found."
    echo "  cp ios/MediaManager/Developer.xcconfig.example ios/MediaManager/Developer.xcconfig"
    exit 1
fi
if [ ! -f "$BRANDING" ]; then
    echo "ERROR: $BRANDING not found."
    echo "  cp ios/MediaManager/Branding.xcconfig.example ios/MediaManager/Branding.xcconfig"
    exit 1
fi

# Validate branding values aren't placeholders
if grep -q 'YOUR_TEAM_ID_HERE' "$XCCONFIG"; then
    echo "ERROR: DEVELOPMENT_TEAM not configured in $XCCONFIG"
    echo "  Replace YOUR_TEAM_ID_HERE with your Apple Developer Team ID"
    exit 1
fi

# Verify protoc is available
if ! command -v protoc &>/dev/null; then
    echo "ERROR: protoc not found. Install with: brew install protobuf"
    exit 1
fi
if ! command -v protoc-gen-swift &>/dev/null; then
    echo "ERROR: protoc-gen-swift not found. Install with: brew install swift-protobuf"
    exit 1
fi
if ! command -v protoc-gen-grpc-swift-2 &>/dev/null; then
    echo "ERROR: protoc-gen-grpc-swift-2 not found. Install with: brew install protoc-gen-grpc-swift"
    exit 1
fi

BUILD_CMD=(
    xcodebuild
    -project "$PROJECT"
    -scheme MediaManager
    -destination "$DESTINATION"
    -configuration "$CONFIGURATION"
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
    build
)

LOG_FILE="$PROJECT_ROOT/data/ios-build.log"
mkdir -p "$(dirname "$LOG_FILE")"
: > "$LOG_FILE"

# Pass 1: Generate proto files. The build phase runs protoc to create
# .pb.swift and .grpc.swift files. Compilation will fail because the
# compiler hasn't indexed the newly-generated source yet — expected.
echo "=== Pass 1: Generating proto stubs ==="
echo "=== Pass 1 ===" >> "$LOG_FILE"
"${BUILD_CMD[@]}" >> "$LOG_FILE" 2>&1 || true

# Pass 2: Compile everything including the generated proto files.
echo "=== Pass 2: Compiling ==="
echo "" >> "$LOG_FILE"
echo "=== Pass 2 ===" >> "$LOG_FILE"
if ! "${BUILD_CMD[@]}" >> "$LOG_FILE" 2>&1; then
    tail -5 "$LOG_FILE"
    echo ""
    echo "Build FAILED ($CONFIGURATION). Full log: $LOG_FILE"
    exit 1
fi

echo ""
echo "Build succeeded ($CONFIGURATION). Full log: $LOG_FILE"
