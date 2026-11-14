#!/usr/bin/env bash
# Capture App Store screenshots of the iOS app.
#
# Usage:
#   lifecycle/capture-ios-screenshots.sh            # both phases
#   lifecycle/capture-ios-screenshots.sh prelogin   # shots 01-03, 07
#   lifecycle/capture-ios-screenshots.sh viewer     # shots 04-06
#
# Creates fresh disposable simulators (never reuses existing ones,
# which may be signed in to a real server), runs the
# MediaManagerUITests/SnapshotTests XCUITests on each, then extracts
# the captured attachments to
# ios/MediaManager/fastlane/screenshots/en-US/<device>/ and copies
# a JPEG of each, as <device>_<screen>.jpg, into
# docs/images/screenshots/ios/: `app-store/` holds the set to upload
# and `reference/` the rest. Both are committed, and the JPEGs in
# `app-store/` are what gets uploaded.
#
# Two phases, because a server shows its terms screen only until the
# account agrees. `prelogin` photographs the screens up to and
# including that one and does not agree, so it can run on every
# device and again later. `viewer` agrees if needed and captures the
# signed-in screens. To photograph an account with some history, run
# `prelogin`, use the account for a while, then run `viewer`.
#
# The app runs in screenshot mode (the -MMSnapshotMode launch arg; see
# ScreenshotMode in ios/MediaManagerCore). Every title, person and
# synopsis is replaced with an invented one, every image with
# generated artwork, and the server address is never rendered. So the
# capture can point at any server, including one with a real library.
#
# Which server and account, first match wins:
#   1. MM_SNAPSHOT_SERVER_URL / MM_SNAPSHOT_USERNAME /
#      MM_SNAPSHOT_PASSWORD in the environment.
#   2. The same three in secrets/ios-screenshots.agent_visible_env
#      (template: secrets/example.ios-screenshots.env).
#   3. The demo server: DEMO_BASE_URL and DEMO_VIEWER_PASSWORD from
#      app_store_demo_setup/secrets/.env, user `viewer`.
#
# Two accounts keep both phases repeatable. `prelogin` uses the account
# above and never agrees to the server's terms, so keep that account
# unused: it then always shows the terms screen. `viewer` uses
# MM_SNAPSHOT_VIEWER_USERNAME / MM_SNAPSHOT_VIEWER_PASSWORD when set
# (an account with some watching and reading behind it, so Home has
# rows to show) and falls back to the first account otherwise. Viewer
# accounts are enough; do not use an admin account.
#
# iPhone Duo: the outer display is captured like any other device. The
# inner display is not captured yet (issue #30); its entry in DEVICES
# is commented out.
#
# Prerequisites:
#   - A reachable server and an account on it (above)
#   - Xcode + iOS simulator runtime installed (latest iOS)
#
# Exit non-zero on any failure. On failure, the temporary
# simulators are LEFT BEHIND for forensics — their UDIDs are
# printed; rerun this script and they will be cleaned up before
# new ones are created.

set -euo pipefail

PHASE="${1:-all}"
case "$PHASE" in
    prelogin) TESTS=(testPreLoginShots) ;;
    viewer)   TESTS=(testViewerShots) ;;
    all)      TESTS=(testPreLoginShots testViewerShots) ;;
    *) echo "usage: $0 [prelogin|viewer|all]" >&2; exit 2 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCAL_ENV_FILE="$PROJECT_ROOT/secrets/ios-screenshots.agent_visible_env"
DEMO_ENV_FILE="$PROJECT_ROOT/app_store_demo_setup/secrets/.env"
IOS_PROJECT="$PROJECT_ROOT/ios/MediaManager/MediaManager.xcodeproj"
OUT_ROOT="$PROJECT_ROOT/ios/MediaManager/fastlane/screenshots/en-US"
DOCS_ROOT="$PROJECT_ROOT/docs/images/screenshots/ios"
BUILD_DIR="$PROJECT_ROOT/ios/MediaManager/build/snapshots"

if [ -n "${MM_SNAPSHOT_SERVER_URL:-}" ]; then
    echo "Server and account: from the environment"
elif [ -f "$LOCAL_ENV_FILE" ]; then
    echo "Server and account: from $LOCAL_ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    . "$LOCAL_ENV_FILE"
    set +a
elif [ -f "$DEMO_ENV_FILE" ]; then
    echo "Server and account: the demo server, from $DEMO_ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    . "$DEMO_ENV_FILE"
    set +a
    MM_SNAPSHOT_SERVER_URL="${DEMO_BASE_URL:-}"
    MM_SNAPSHOT_USERNAME="viewer"
    MM_SNAPSHOT_PASSWORD="${DEMO_VIEWER_PASSWORD:-}"
else
    echo "ERROR: no server configured. Set MM_SNAPSHOT_SERVER_URL, MM_SNAPSHOT_USERNAME and" >&2
    echo "MM_SNAPSHOT_PASSWORD, or copy secrets/example.ios-screenshots.env to" >&2
    echo "secrets/ios-screenshots.agent_visible_env and fill it in." >&2
    exit 1
fi
: "${MM_SNAPSHOT_SERVER_URL:?MM_SNAPSHOT_SERVER_URL is empty}"
: "${MM_SNAPSHOT_USERNAME:?MM_SNAPSHOT_USERNAME is empty}"
: "${MM_SNAPSHOT_PASSWORD:?MM_SNAPSHOT_PASSWORD is empty}"

runtime_for_device_type() {
    # The newest installed iOS runtime that supports the device type.
    # Not simply the newest runtime: some devices (the iPhone Duo) run
    # only on a runtime of their own.
    local type_id="$1"
    xcrun simctl list runtimes -j \
        | jq -r --arg t "$type_id" '
            .runtimes
            | map(select(.isAvailable and (.platform == "iOS")
                         and ([.supportedDeviceTypes[]?.identifier] | index($t) != null)))
            | sort_by(.version)
            | last.identifier // empty'
}

# Device matrix: the newest simulator in each class App Store Connect
# asks for (developer.apple.com/help/app-store-connect/reference/
# screenshot-specifications, read 2026-11-13).
#   - iPhone with Dynamic Island, large display: 1320x2868.
#   - iPhone with Dynamic Island, medium display: 1206x2622.
#   - iPad 13" display: 2064x2752.
# Smaller and older classes are scaled from these by App Store Connect.
#   - iPhone Duo, outer display 1398x2034 and inner display 2007x2853
#     (required from April 2027 for apps built with the iOS 27.1 SDK
#     or later).
# Each entry is "simulator name|device type name|option"; an exact
# device type name wins over a partial match. The option `unfold`
# waits for the simulator to be unfolded by hand before the test runs;
# nothing uses it while the Duo's inner display is disabled.
# MM_SNAPSHOT_DEVICES, a space-separated list of simulator names,
# limits a run to some of them.
DEVICES=(
    "MM-Snap-iPhone-18-Pro-Max|iPhone 18 Pro Max|"
    "MM-Snap-iPhone-18-Pro|iPhone 18 Pro|"
    "MM-Snap-iPad-Pro-13|iPad Pro 13-inch|"
    "MM-Snap-iPhone-Duo-Outer|iPhone Duo|"
    # Disabled: the inner display cannot be captured yet (issue #30).
    # The simulator can be unfolded by hand and `wait_for_unfold` sees
    # it, but the test's screenshot is of the outer display.
    # "MM-Snap-iPhone-Duo-Inner|iPhone Duo|unfold"
)
if [ -n "${MM_SNAPSHOT_DEVICES:-}" ]; then
    SELECTED=()
    for entry in "${DEVICES[@]}"; do
        case " $MM_SNAPSHOT_DEVICES " in *" ${entry%%|*} "*) SELECTED+=("$entry") ;; esac
    done
    DEVICES=("${SELECTED[@]}")
fi

# Pixel width of the Duo's inner display, used to find it among the
# simulator's displays.
DUO_INNER_WIDTH=2007

wait_for_unfold() {
    # Blocks until the simulator's inner display shows a picture. A
    # folded Duo reports the inner display as a black frame, which
    # compresses to a few kilobytes; a lit one is hundreds.
    local udid="$1" probe="$BUILD_DIR/unfold-probe.png" display size waited=0
    display=$(xcrun simctl io "$udid" screenshot "$probe" 2>&1 \
        | sed -nE "s/.*--display=([0-9]+) .* ${DUO_INNER_WIDTH}x[0-9]+.*/\\1/p" | head -n 1)
    if [ -z "$display" ]; then
        echo "    ERROR: could not find the inner display on $udid" >&2
        return 1
    fi
    echo "    Waiting for the simulator to be unfolded: use the fold control in Device Hub."
    while [ "$waited" -lt 900 ]; do
        rm -f "${probe:?}"
        xcrun simctl io "$udid" screenshot --display="$display" "$probe" >/dev/null 2>&1 || true
        size=$(stat -f%z "$probe" 2>/dev/null || echo 0)
        if [ "$size" -gt 100000 ]; then
            echo "    Unfolded."
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
    echo "    ERROR: the simulator was not unfolded within 15 minutes" >&2
    return 1
}

resolve_device_type() {
    # The device type whose name IS the requested string, else the
    # most recent one whose name contains it. "iPhone 18 Pro" must not
    # pick up "iPhone 18 Pro Max"; "iPad Pro 13-inch" matches the M4
    # and M5 variants and takes the lexicographically greatest
    # (== newest hardware generation).
    local needle="$1"
    xcrun simctl list devicetypes -j \
        | jq -r --arg n "$needle" '
            .devicetypes as $all
            | ($all | map(select(.name == $n)) | first.identifier) //
              ($all | map(select(.name | test($n; "i"))) | sort_by(.identifier) | last.identifier) //
              empty'
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

# One run per device and test. Each test starts from a fresh install
# (the app's own policy gate, no saved server), so each gets its own
# new simulator.
RUNS=()
for entry in "${DEVICES[@]}"; do
    for t in "${TESTS[@]}"; do
        RUNS+=("$entry|$t")
    done
done

for run in "${RUNS[@]}"; do
    IFS='|' read -r sim_name type_needle option test_name <<< "$run"
    run_name="$sim_name-$test_name"

    # The signed-in phase uses the account with history, when there is one.
    run_username="$MM_SNAPSHOT_USERNAME"
    run_password="$MM_SNAPSHOT_PASSWORD"
    if [ "$test_name" = "testViewerShots" ] && [ -n "${MM_SNAPSHOT_VIEWER_USERNAME:-}" ]; then
        run_username="$MM_SNAPSHOT_VIEWER_USERNAME"
        run_password="${MM_SNAPSHOT_VIEWER_PASSWORD:?MM_SNAPSHOT_VIEWER_PASSWORD is empty}"
    fi

    echo
    echo "==> $sim_name ($type_needle): $test_name"

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
    runtime_id=$(runtime_for_device_type "$type_id")
    if [ -z "$runtime_id" ]; then
        echo "    ERROR: no installed iOS runtime supports $type_id (Xcode > Settings > Components)" >&2
        OVERALL_STATUS=1
        continue
    fi
    echo "    Runtime: $runtime_id"
    udid=$(xcrun simctl create "$sim_name" "$type_id" "$runtime_id")
    echo "    Created simulator $udid"
    xcrun simctl boot "$udid"
    # The conventional store status bar: 9:41, full battery and signal.
    # Cosmetic, so a device that does not support the override is fine.
    xcrun simctl bootstatus "$udid" >/dev/null 2>&1 || true
    xcrun simctl status_bar "$udid" override --time "9:41" \
        --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularBars 4 \
        >/dev/null 2>&1 || true
    if [ "$option" = "unfold" ]; then
        xcrun simctl bootstatus "$udid" >/dev/null 2>&1 || true
        if ! wait_for_unfold "$udid"; then
            LEFTOVER_UDIDS+=("$udid ($sim_name)")
            OVERALL_STATUS=1
            continue
        fi
    fi

    # Files are replaced by name, not the whole directory: a `viewer`
    # run must not delete what `prelogin` captured.
    out_device_dir="$OUT_ROOT/$sim_name"
    device_name="${sim_name#MM-Snap-}"
    mkdir -p "$out_device_dir"

    xcresult="$BUILD_DIR/$run_name.xcresult"
    rm -rf "${xcresult:?}"

    # TEST_RUNNER_* prefix instructs xcodebuild to forward the env
    # var (with the prefix stripped) to the running test process.
    # XCUITest then propagates these into the app via
    # app.launchEnvironment — see SnapshotTests.setUp.
    set +e
    TEST_RUNNER_MM_SNAPSHOT_SERVER_URL="$MM_SNAPSHOT_SERVER_URL" \
    TEST_RUNNER_MM_SNAPSHOT_USERNAME="$run_username" \
    TEST_RUNNER_MM_SNAPSHOT_PASSWORD="$run_password" \
    xcodebuild \
        -project "$IOS_PROJECT" \
        -scheme MediaManager \
        -sdk iphonesimulator \
        -destination "platform=iOS Simulator,id=$udid" \
        -derivedDataPath "$BUILD_DIR/DerivedData" \
        "-only-testing:MediaManagerUITests/SnapshotTests/$test_name" \
        -resultBundlePath "$xcresult" \
        CODE_SIGNING_ALLOWED=NO \
        test-without-building \
        > "$BUILD_DIR/$run_name.log" 2>&1
    test_status=$?
    set -e

    if [ $test_status -ne 0 ]; then
        echo "    ERROR: $test_name failed on $sim_name (see $BUILD_DIR/$run_name.log)" >&2
        echo "    Leaving simulator $udid alive for forensics." >&2
        LEFTOVER_UDIDS+=("$udid ($sim_name)")
        OVERALL_STATUS=1
        continue
    fi

    # Extract attachments. xcresulttool writes a manifest.json that
    # maps the on-disk file names (UUID-y) to the attachment names
    # we set in the test (`01-server-setup`, etc.). Rename to the
    # human form.
    attach_tmp="$BUILD_DIR/$run_name-attachments"
    rm -rf "${attach_tmp:?}"
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
        # The committed name and folder for each shot. `app-store` is
        # the set to upload, in the order listed in
        # docs/SCREENSHOT_PROCEDURES.md; `reference` is kept for the
        # record and not uploaded.
        case "$stem" in
            04-home)            set_dir=app-store; screen=home ;;
            05-catalog-movies)  set_dir=app-store; screen=movies ;;
            06-title-detail-tv) set_dir=app-store; screen=tv-show ;;
            08-books)           set_dir=app-store; screen=books ;;
            09-music)           set_dir=app-store; screen=music ;;
            10-wish-list)       set_dir=app-store; screen=wish-list ;;
            01-server-setup)    set_dir=app-store; screen=server-setup ;;
            02-app-policy)      set_dir=reference; screen=app-policy ;;
            03-login)           set_dir=reference; screen=login ;;
            07-server-terms)    set_dir=reference; screen=server-terms ;;
            *) continue ;;
        esac
        src="$attach_tmp/$file"
        if [ ! -f "$src" ]; then
            echo "    WARN: attachment $name -> $file missing on disk" >&2
            continue
        fi
        cp "$src" "$out_device_dir/$stem.png"
        # The committed copy, and the one to upload:
        # <device>_<screen>.jpg, JPEG at quality 90. About a fifth the
        # size of the PNG, which matters because git keeps every past
        # capture forever. Uploading what is committed keeps the record
        # and the store listing the same.
        mkdir -p "$DOCS_ROOT/$set_dir"
        sips -s format jpeg -s formatOptions 90 "$src" \
            --out "$DOCS_ROOT/$set_dir/${device_name}_${screen}.jpg" >/dev/null
        count=$((count + 1))
    done <<< "$mapping"

    echo "    Captured $count screenshots → $out_device_dir/"

    # Success path: tear down the simulator.
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl delete "$udid"
done

echo
if [ $OVERALL_STATUS -eq 0 ]; then
    echo "==> Done. PNGs under $OUT_ROOT/ and $DOCS_ROOT/"
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
