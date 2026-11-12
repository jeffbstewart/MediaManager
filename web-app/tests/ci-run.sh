#!/usr/bin/env bash
# Run the Playwright harness against a dev server this script owns.
#
# The harness (tests/harness.mjs) assumes `ng serve` is already listening
# on port 4200; it never starts one. That is fine at a desk where the dev
# server is long-lived, and wrong everywhere else (CI, a cloud session, a
# fresh clone). This wrapper closes the gap:
#
#   1. Make sure a Chromium exists. If the build this Playwright version
#      pins is missing but the host ships another under
#      PLAYWRIGHT_BROWSERS_PATH (cloud sandboxes that block
#      cdn.playwright.dev do), point Playwright at the newest one via
#      PW_CHROMIUM_EXECUTABLE (read by playwright.config.ts). No download.
#   2. If something already answers on 4200, reuse it and leave it alone.
#   3. Otherwise run the proto codegen, start `ng serve` in the background,
#      and wait for it to answer (up to CI_RUN_BOOT_TIMEOUT seconds, 240).
#   4. Run the harness with the arguments given (both suites when none),
#      with a 90 s per-test timeout unless PW_TEST_TIMEOUT_MS is set, and a
#      15 s expect() timeout unless PW_EXPECT_TIMEOUT_MS is set. With
#      `capture` as the first argument, run the screenshot capture
#      (tests/visual/capture.mjs) instead, passing the remaining arguments.
#   5. Stop the server we started. Exit with the harness's exit code.
#
# Usage, from web-app/:
#   tests/ci-run.sh                 # both suites
#   tests/ci-run.sh axe             # one suite, same args as the harness
#   CI_RUN_INSTALL_BROWSERS=1 tests/ci-run.sh   # download the pinned Chromium first (needs network)
#   tests/ci-run.sh capture --out ../data/screenshots/<name> [--themes marquee]
#   tests/ci-run.sh docs                       # documentation screenshots
#
# Findings live under tests/.last-run/ as always; the first line of
# summary.txt is the verdict. Never pipe this script's stderr away: the
# harness draws its progress there.
set -uo pipefail
cd "$(dirname "$0")/.."

PORT="${CI_RUN_PORT:-4200}"
BOOT_TIMEOUT="${CI_RUN_BOOT_TIMEOUT:-240}"
URL="http://localhost:${PORT}/"
SERVER_PID=""

port_up() {
  curl -s -o /dev/null --max-time 2 "$URL"
}

stop_server() {
  [ -n "$SERVER_PID" ] || return 0
  echo "ci-run: stopping dev server (pid $SERVER_PID)" >&2
  if [[ "${OS:-}" == "Windows_NT" ]]; then
    # Under Git Bash, $! is an MSYS pid that taskkill does not know.
    # Find the Windows process listening on the port and kill its tree.
    local winpid
    winpid=$(powershell -NoProfile -Command \
      "(Get-NetTCPConnection -LocalPort $PORT -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess" 2>/dev/null | tr -d '\r')
    if [ -n "$winpid" ]; then
      taskkill //F //T //PID "$winpid" >/dev/null 2>&1 || true
    fi
    kill -TERM "$SERVER_PID" 2>/dev/null || true
  else
    kill -TERM -- "-$SERVER_PID" 2>/dev/null || kill -TERM "$SERVER_PID" 2>/dev/null || true
  fi
  SERVER_PID=""
}
trap stop_server EXIT

if [ "${CI_RUN_INSTALL_BROWSERS:-0}" = "1" ]; then
  echo "ci-run: installing Chromium for Playwright" >&2
  npx playwright install chromium --with-deps || npx playwright install chromium || exit 1
fi

# Use the host's Chromium when the pinned build is absent. Playwright
# reports the executable it wants via `--dry-run`; if that path does not
# exist and PW_CHROMIUM_EXECUTABLE is not already set, look under the
# browsers directory for any installed headless shell or full Chromium
# and take the newest build number.
if [ -z "${PW_CHROMIUM_EXECUTABLE:-}" ]; then
  expected=$(npx playwright install --dry-run chromium 2>/dev/null \
    | sed -n 's/^ *Install location: *//p' | head -1 | tr -d '\r')
  if [ -n "$expected" ] && [ ! -d "$expected" ]; then
    browsers_dir="${PLAYWRIGHT_BROWSERS_PATH:-$(dirname "$expected")}"
    candidate=""
    for pattern in "chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell" \
                   "chromium_headless_shell-*/chrome-headless-shell-mac*/chrome-headless-shell" \
                   "chromium_headless_shell-*/chrome-headless-shell-win64/chrome-headless-shell.exe" \
                   "chromium-*/chrome-linux64/chrome" \
                   "chromium-*/chrome-linux/chrome" \
                   "chromium-*/chrome-win64/chrome.exe"; do
      for f in "$browsers_dir"/$pattern; do
        [ -x "$f" ] && candidate="$f"   # glob order is ascending, so the last match is the newest build
      done
      [ -n "$candidate" ] && break
    done
    if [ -n "$candidate" ]; then
      export PW_CHROMIUM_EXECUTABLE="$candidate"
      echo "ci-run: pinned Chromium missing at $expected; using host browser $candidate" >&2
    else
      echo "ci-run: pinned Chromium missing at $expected and no host browser found under $browsers_dir" >&2
      echo "ci-run: set CI_RUN_INSTALL_BROWSERS=1 (needs network) or PW_CHROMIUM_EXECUTABLE" >&2
    fi
  fi
fi

if port_up; then
  echo "ci-run: reusing dev server already on port $PORT" >&2
else
  echo "ci-run: generating protos" >&2
  node tests/scripts/gen-proto.mjs || exit 1
  echo "ci-run: starting ng serve on port $PORT" >&2
  mkdir -p tests/.last-run
  if [[ "${OS:-}" == "Windows_NT" ]]; then
    npx ng serve --port "$PORT" >tests/.last-run/ng-serve.log 2>&1 &
  else
    setsid npx ng serve --port "$PORT" >tests/.last-run/ng-serve.log 2>&1 &
  fi
  SERVER_PID=$!
  waited=0
  until port_up; do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "ci-run: ng serve exited before answering; see tests/.last-run/ng-serve.log" >&2
      exit 1
    fi
    if [ "$waited" -ge "$BOOT_TIMEOUT" ]; then
      echo "ci-run: ng serve did not answer within ${BOOT_TIMEOUT}s; see tests/.last-run/ng-serve.log" >&2
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  echo "ci-run: dev server up after ${waited}s" >&2
fi

# Per-test timeout for the harness run. The config default (30 s) assumes a
# desk machine; CI boxes with few cores and a dev server competing for them
# time out on the slower axe specs. Override by setting the variable yourself.
export PW_TEST_TIMEOUT_MS="${PW_TEST_TIMEOUT_MS:-90000}"
export PW_EXPECT_TIMEOUT_MS="${PW_EXPECT_TIMEOUT_MS:-15000}"

if [ "${1:-}" = "capture" ]; then
  shift
  node tests/visual/capture.mjs "$@"
elif [ "${1:-}" = "docs" ]; then
  shift
  node tests/visual/docs.mjs "$@"
else
  node tests/harness.mjs "$@"
fi
status=$?
stop_server
trap - EXIT
exit $status
