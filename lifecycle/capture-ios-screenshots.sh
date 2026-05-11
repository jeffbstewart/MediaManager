#!/usr/bin/env bash
# Capture App Store screenshots of the iOS app against the public
# demo server (https://appstoredemo.15mcmahon.net:8443).
#
# Sources app_store_demo_setup/secrets/.env, creates fresh
# disposable simulators (never reuses existing ones — those may be
# paired with the production NAS server), runs the
# MediaManagerUITests/SnapshotTests/testViewerShots XCUITest on each,
# then extracts the captured attachments to
# ios/MediaManager/fastlane/screenshots/en-US/<device>/.
#
# The demo URL is never typed into a captured screenshot — the
# -MMSnapshotMode launch arg makes views suppress server-host
# rendering. See ios/MediaManager/MediaManager/Services/SnapshotMode.swift.
#
# Prerequisites:
#   - secrets/.env populated (./app_store_demo_setup/secrets/.env)
#   - The 4 screenshot accounts exist on the demo server
#     (run seed-users first if not)
#   - Xcode + iOS simulator runtime installed (latest iOS)
#
# Exit non-zero on any failure. On failure, the temporary
# simulators are LEFT BEHIND for forensics — their UDIDs are
# printed; rerun this script and they will be cleaned up before
# new ones are created.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/app_store_demo_setup/secrets/.env"
IOS_PROJECT="$PROJECT_ROOT/ios/MediaManager/MediaManager.xcodeproj"
OUT_ROOT="$PROJECT_ROOT/ios/MediaManager/fastlane/screenshots/en-US"
BUILD_DIR="$PROJECT_ROOT/ios/MediaManager/build/snapshots"

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: $ENV_FILE not found. Copy app_store_demo_setup/secrets/example.env to .env and fill it in." >&2
    exit 1
fi

# Source the .env. Lines are KEY=VALUE; export each.
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# Required vars (subset — we only need viewer for the 6-shot scope).
: "${DEMO_BASE_URL:?DEMO_BASE_URL must be set in $ENV_FILE}"
: "${DEMO_VIEWER_PASSWORD:?DEMO_VIEWER_PASSWORD must be set in $ENV_FILE}"

# Pick the latest installed iOS simulator runtime dynamically — the
# build host's Xcode version determines what's available, and we
# want to track Apple's current device classes without hard-coding
# a runtime ID.
RUNTIME_ID=$(xcrun simctl list runtimes -j \
    | jq -r '.runtimes
              | map(select(.isAvailable and (.platform == "iOS")))
              | sort_by(.version)
              | last.identifier // empty')
if [ -z "$RUNTIME_ID" ]; then
    echo "ERROR: no available iOS simulator runtime. Install one via Xcode > Settings > Components." >&2
    exit 1
fi
echo "Using iOS runtime: $RUNTIME_ID"

# Device matrix. App Store Connect's iPhone screenshot slot accepts
# 1242x2688 (6.5" class, iPhone 11 Pro Max) or 1284x2778 (6.7"
# class, iPhone 12/13 Pro Max). Newer Pro Max devices (14/15/16
# Pro Max) capture at 1290x2796 / 1320x2868 which ASC rejects for
# this app's current slot — so we capture on iPhone 13 Pro Max
# (1284x2778 native) and let ASC auto-derive larger 6.9" sizes.
#
# iPad 13" Pro (M5 native 2064x2752) maps onto ASC's 13" iPad slot
# without resize.
DEVICES=(
    "MM-Snap-iPhone-13-Pro-Max|iPhone 13 Pro Max"
    "MM-Snap-iPad-Pro-13|iPad Pro 13-inch"
)

resolve_device_type() {
    # Find the most recent device-type identifier whose human name
    # CONTAINS the requested substring. "iPad Pro 13-inch" matches
    # both M4 and M5 variants; we pick the lexicographically-greatest
    # (== newest hardware generation).
    local needle="$1"
    xcrun simctl list devicetypes -j \
        | jq -r --arg n "$needle" '
            .devicetypes
            | map(select(.name | test($n; "i")))
            | sort_by(.identifier)
            | last.identifier // empty'
}

cleanup_named_simulator() {
    # Delete any simulator with our snapshot prefix name — covers
    # leftovers from a prior failed run AND any new ones we create.
    local name="$1"
    local udid
    udid=$(xcrun simctl list devices -j \
        | jq -r --arg n "$name" '
            .devices
            | to_entries
            | map(.value[])
            | map(select(.name == $n))
            | .[].udid // empty' \
        | head -n 1)
    if [ -n "$udid" ]; then
        echo "  Deleting leftover simulator $name ($udid)"
        xcrun simctl delete "$udid" 2>/dev/null || true
    fi
}

mkdir -p "$BUILD_DIR" "$OUT_ROOT"

# Build once for the simulator destination (build-for-testing) so
# each device run reuses the same .xctestrun bundle. The actual
# test invocation per-device just runs the built bundle.
echo
echo "==> Building MediaManagerUITests..."
xcodebuild \
    -project "$IOS_PROJECT" \
    -scheme MediaManager \
    -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -configuration Debug \
    -derivedDataPath "$BUILD_DIR/DerivedData" \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing \
    > "$BUILD_DIR/build.log" 2>&1
echo "    Build OK."

OVERALL_STATUS=0
LEFTOVER_UDIDS=()

for entry in "${DEVICES[@]}"; do
    sim_name="${entry%%|*}"
    type_needle="${entry##*|}"

    echo
    echo "==> $sim_name ($type_needle)"

    type_id=$(resolve_device_type "$type_needle")
    if [ -z "$type_id" ]; then
        echo "    ERROR: no device type matched '$type_needle'" >&2
        OVERALL_STATUS=1
        continue
    fi
    echo "    Device type: $type_id"

    # Always start from a fresh simulator — never reuse one that
    # might be paired with the production server.
    cleanup_named_simulator "$sim_name"
    udid=$(xcrun simctl create "$sim_name" "$type_id" "$RUNTIME_ID")
    echo "    Created simulator $udid"
    xcrun simctl boot "$udid"

    out_device_dir="$OUT_ROOT/$sim_name"
    rm -rf "$out_device_dir"
    mkdir -p "$out_device_dir"

    xcresult="$BUILD_DIR/$sim_name.xcresult"
    rm -rf "$xcresult"

    # TEST_RUNNER_* prefix instructs xcodebuild to forward the env
    # var (with the prefix stripped) to the running test process.
    # XCUITest then propagates these into the app via
    # app.launchEnvironment — see SnapshotTests.setUp.
    set +e
    TEST_RUNNER_MM_SNAPSHOT_SERVER_URL="$DEMO_BASE_URL" \
    TEST_RUNNER_MM_SNAPSHOT_USERNAME="viewer" \
    TEST_RUNNER_MM_SNAPSHOT_PASSWORD="$DEMO_VIEWER_PASSWORD" \
    xcodebuild \
        -project "$IOS_PROJECT" \
        -scheme MediaManager \
        -sdk iphonesimulator \
        -destination "platform=iOS Simulator,id=$udid" \
        -derivedDataPath "$BUILD_DIR/DerivedData" \
        -only-testing:MediaManagerUITests/SnapshotTests/testViewerShots \
        -resultBundlePath "$xcresult" \
        CODE_SIGNING_ALLOWED=NO \
        test-without-building \
        > "$BUILD_DIR/$sim_name.log" 2>&1
    test_status=$?
    set -e

    if [ $test_status -ne 0 ]; then
        echo "    ERROR: test failed for $sim_name (see $BUILD_DIR/$sim_name.log)" >&2
        echo "    Leaving simulator $udid alive for forensics." >&2
        LEFTOVER_UDIDS+=("$udid ($sim_name)")
        OVERALL_STATUS=1
        continue
    fi

    # Extract attachments. xcresulttool writes a manifest.json that
    # maps the on-disk file names (UUID-y) to the attachment names
    # we set in the test (`01-server-setup`, etc.). Rename to the
    # human form.
    attach_tmp="$BUILD_DIR/$sim_name-attachments"
    rm -rf "$attach_tmp"
    xcrun xcresulttool export attachments \
        --path "$xcresult" \
        --output-path "$attach_tmp" > /dev/null

    if [ ! -f "$attach_tmp/manifest.json" ]; then
        echo "    ERROR: no manifest.json produced by xcresulttool" >&2
        OVERALL_STATUS=1
        continue
    fi

    # Manifest schema: top-level array of test entries, each with
    # an `attachments` array of {suggestedHumanReadableName,
    # exportedFileName}. Xcode appends a `_0_<UUID>.png` suffix to
    # the human-readable name to disambiguate same-named attachments
    # within a run; we strip it back to our chosen stem (e.g.
    # `01-server-setup`).
    mapping=$(jq -r '
        .[] | .attachments[]
        | "\(.suggestedHumanReadableName // .name // "unknown")\t\(.exportedFileName)"
    ' "$attach_tmp/manifest.json")

    count=0
    while IFS=$'\t' read -r name file; do
        [ -z "$name" ] && continue
        [ -z "$file" ] && continue
        # Strip Xcode's `_0_<UUID>.png` disambiguation suffix to get
        # back to the stem we set via XCTAttachment.name. Keep only
        # the numbered-shot stems (`NN-name`); skip Xcode-generated
        # debug attachments (UI hierarchy dumps, screen recordings,
        # synthesized event logs).
        stem=$(printf '%s' "$name" | sed -E 's/_[0-9]+_[A-F0-9-]+\.[A-Za-z0-9]+$//')
        case "$stem" in
            0[0-9]-*) ;;
            *) continue ;;
        esac
        src="$attach_tmp/$file"
        if [ ! -f "$src" ]; then
            echo "    WARN: attachment $name -> $file missing on disk" >&2
            continue
        fi
        cp "$src" "$out_device_dir/$stem.png"
        count=$((count + 1))
    done <<< "$mapping"

    echo "    Captured $count screenshots → $out_device_dir/"

    # Success path: tear down the simulator.
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl delete "$udid"
done

echo
if [ $OVERALL_STATUS -eq 0 ]; then
    echo "==> Done. PNGs under $OUT_ROOT/"
else
    echo "==> FAILED. Build/test logs in $BUILD_DIR/." >&2
    if [ ${#LEFTOVER_UDIDS[@]} -gt 0 ]; then
        echo "    Leftover simulators (rerun this script to clean up):" >&2
        for u in "${LEFTOVER_UDIDS[@]}"; do
            echo "      $u" >&2
        done
    fi
fi
exit $OVERALL_STATUS
