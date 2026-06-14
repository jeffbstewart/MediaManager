#!/bin/bash
# Generate Swift proto + gRPC stubs into the MediaManagerCore SPM package.
#
# Runs protoc against proto/*.proto and writes outputs to
# ios/MediaManagerCore/Sources/MediaManagerProtos/. These files are
# gitignored — they're regenerated whenever the proto schema changes.
#
# Called automatically by lifecycle/ios-build.sh before xcodebuild.
# Run standalone when you've edited a .proto and want a clean Cmd+B in
# Xcode without going through the full lifecycle build.
#
# Prerequisites:
#   brew install protobuf swift-protobuf protoc-gen-grpc-swift

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
PROTO_DIR="$PROJECT_ROOT/proto"
OUT_DIR="$PROJECT_ROOT/ios/MediaManagerCore/Sources/MediaManagerProtos"

if [ ! -d "$PROTO_DIR" ]; then
    echo "ERROR: proto directory not found: $PROTO_DIR" >&2
    exit 1
fi

if ! command -v protoc &>/dev/null; then
    echo "ERROR: protoc not found. Install with: brew install protobuf" >&2
    exit 1
fi
if ! command -v protoc-gen-swift &>/dev/null; then
    echo "ERROR: protoc-gen-swift not found. Install with: brew install swift-protobuf" >&2
    exit 1
fi
if ! command -v protoc-gen-grpc-swift-2 &>/dev/null; then
    echo "ERROR: protoc-gen-grpc-swift-2 not found. Install with: brew install protoc-gen-grpc-swift" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

protoc \
    --proto_path="$PROTO_DIR" \
    --swift_out="$OUT_DIR" \
    --swift_opt=Visibility=Public \
    --grpc-swift-2_out="$OUT_DIR" \
    --grpc-swift-2_opt=Visibility=Public \
    "$PROTO_DIR"/*.proto

# Drop the empty-target placeholder once real sources exist.
rm -f "$OUT_DIR/_Empty.swift"

count=$(find "$OUT_DIR" -maxdepth 1 -name '*.swift' | wc -l | tr -d ' ')
echo "Generated $count Swift files into $OUT_DIR"
