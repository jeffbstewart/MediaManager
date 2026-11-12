#!/usr/bin/env bash
# Pre-submit gate: runs the full Playwright suite via the harness
# and exits non-zero on any failure. Safe to call directly OR to
# wire as a git pre-push hook:
#
#   ln -s ../../lifecycle/pre-submit.sh .git/hooks/pre-push
#
# The harness writes structured artifacts under
# web-app/tests/.last-run/. To inspect failures:
#
#   cat web-app/tests/.last-run/summary.txt    # one-line per failure
#   cat web-app/tests/.last-run/failures.md    # trimmed per-failure detail
set -euo pipefail

cd "$(dirname "$0")/.."

cd web-app
# Regenerate proto-derived TS types up-front so a stale generated dir
# never silently flips the suite green or red. Cheap when nothing's
# changed (gen-proto.mjs short-circuits via mtime checks would be ideal;
# for now it's a few hundred ms even when re-running).
node tests/scripts/gen-proto.mjs
node tests/harness.mjs "$@"
