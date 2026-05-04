#!/bin/bash
# Run the iOS test suite (UI test target — currently the only one in
# the project; XCTests that don't drive the app via XCUIApplication
# live there too, e.g. ReaderBridgeTests).
#
# Usage:
#   lifecycle/ios-test.sh                          # all tests
#   lifecycle/ios-test.sh ReaderBridgeTests        # one test class
#   lifecycle/ios-test.sh ReaderBridgeTests/testGetTocReturnsThreeChapters
#
# Output streams to stderr live (xcodebuild's progress) and to
# data/ios-test.log on disk for after-the-fact inspection. On
# failure the last 20 lines of the log are tailed; full log path is
# printed either way.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
PROJECT="$PROJECT_ROOT/ios/MediaManager/MediaManager.xcodeproj"

# Prefer the simulator already booted (so back-to-back runs don't
# pay a cold-boot penalty); else pick the first available iPhone
# from `simctl list`. The `Booted` / `(...)` patterns in the
# `simctl list devices available` text output are stable across
# Xcode versions and easier to scan than the JSON schema.
DESTINATION_ID="$(xcrun simctl list devices available \
    | grep -E "iPhone.*\(Booted\)" \
    | head -1 \
    | grep -oE "\([0-9A-F-]{36}\)" \
    | head -1 \
    | tr -d '()')"
if [ -z "$DESTINATION_ID" ]; then
    DESTINATION_ID="$(xcrun simctl list devices available \
        | grep -E "iPhone " \
        | head -1 \
        | grep -oE "\([0-9A-F-]{36}\)" \
        | head -1 \
        | tr -d '()')"
fi
if [ -z "$DESTINATION_ID" ]; then
    echo "ERROR: no available iOS simulator found." >&2
    echo "  Run 'xcrun simctl list devices available' to see what Xcode has." >&2
    exit 1
fi
echo "Using simulator $DESTINATION_ID"

LOG_FILE="$PROJECT_ROOT/data/ios-test.log"
mkdir -p "$(dirname "$LOG_FILE")"
: > "$LOG_FILE"

# -only-testing one of the args produces a single subset; bare run
# tests everything. xcodebuild accepts repeated -only-testing flags.
ONLY_ARGS=()
for arg in "$@"; do
    ONLY_ARGS+=("-only-testing" "MediaManagerUITests/$arg")
done

CMD=(
    xcodebuild test
    -project "$PROJECT"
    -scheme MediaManager
    -destination "id=$DESTINATION_ID"
    ${ONLY_ARGS[@]+"${ONLY_ARGS[@]}"}
)

echo "=== ${CMD[*]} ===" >> "$LOG_FILE"
# `tee` streams the full xcodebuild output to disk and to the
# terminal in real time. No grep filter on the pipe — earlier
# attempts to filter for `Test Case` lines silently masked
# diagnostic output during pre-test phases (build, simulator
# install) and made hangs look like silent runs. Any post-hoc
# triage is grep-against-the-log territory.
"${CMD[@]}" 2>&1 | tee -a "$LOG_FILE"
EXIT=${PIPESTATUS[0]}

# `xcodebuild test` returns 65 on test failure, 70 on uncategorised
# errors, 0 on pass. Trust the exit code first, then sanity-check
# against the marker line in case of an output-buffer truncation.
if [ "$EXIT" -eq 0 ] || grep -q "** TEST SUCCEEDED **" "$LOG_FILE"; then
    echo ""
    echo "Tests PASSED. Full log: $LOG_FILE"
    exit 0
fi

echo ""
echo "Tests FAILED (xcodebuild exit $EXIT). Last 20 lines of $LOG_FILE:"
tail -20 "$LOG_FILE"
echo ""
echo "Full log: $LOG_FILE"
exit 1
