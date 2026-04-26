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
node tests/harness.mjs "$@"
