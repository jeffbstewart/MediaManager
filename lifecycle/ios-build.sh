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

# Verify Developer.xcconfig exists
XCCONFIG="$PROJECT_ROOT/ios/MediaManager/Developer.xcconfig"
if [ ! -f "$XCCONFIG" ]; then
    echo "ERROR: $XCCONFIG not found. Copy from Developer.xcconfig.example and fill in your Team ID."
    exit 1
fi

# Load iOS secrets
IOS_ENV="$PROJECT_ROOT/secrets/ios.agent_visible_env"
if [ ! -f "$IOS_ENV" ]; then
    echo "ERROR: $IOS_ENV not found."
    echo "  cp secrets/example.ios.env secrets/ios.agent_visible_env"
    echo "  Then fill in DEVELOPMENT_TEAM and PRIVACY_POLICY_URL"
    exit 1
fi
while IFS='=' read -r key value; do
    key="${key//$'\r'/}"
    value="${value//$'\r'/}"
    [[ -z "$key" || "$key" =~ ^# ]] && continue
    export "$key"="$value"
done < "$IOS_ENV"

# Validate required values
if [ -z "${DEVELOPMENT_TEAM:-}" ] || [[ "$DEVELOPMENT_TEAM" == *"YOUR_TEAM_ID_HERE"* ]]; then
    echo "ERROR: DEVELOPMENT_TEAM not set in $IOS_ENV"
    echo "  Find your Team ID in the Apple Developer portal under Membership"
    exit 1
fi
if [ -z "${PRIVACY_POLICY_URL:-}" ] || [[ "$PRIVACY_POLICY_URL" == *"YOUR_PRIVACY_POLICY_URL_HERE"* ]]; then
    echo "ERROR: PRIVACY_POLICY_URL not set in $IOS_ENV"
    echo "  Generate a privacy policy at https://www.termsfeed.com/privacy-policy-generator/"
    echo "  Then set PRIVACY_POLICY_URL in secrets/ios.agent_visible_env"
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
    "PRIVACY_POLICY_URL=$PRIVACY_POLICY_URL"
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
    build
)

# Pass 1: Generate proto files. The build phase runs protoc to create
# .pb.swift and .grpc.swift files. Compilation will fail because the
# compiler hasn't indexed the newly-generated source yet — expected.
echo "=== Pass 1: Generating proto stubs ==="
"${BUILD_CMD[@]}" >/dev/null 2>&1 || true

# Pass 2: Compile everything including the generated proto files.
echo "=== Pass 2: Compiling ==="
"${BUILD_CMD[@]}" 2>&1 | tail -5

echo ""
echo "Build succeeded ($CONFIGURATION)."
