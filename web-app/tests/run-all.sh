#!/usr/bin/env bash
# Thin wrapper around tests/harness.mjs — forwards args verbatim.
# The harness is the canonical runner; it captures structured
# results under tests/.last-run/ so you don't have to re-run
# specs to inspect failures. See harness.mjs for details.
#
# Usage:
#   tests/run-all.sh              # both suites
#   tests/run-all.sh axe          # only axe
#   tests/run-all.sh functional   # only functional
set -euo pipefail
cd "$(dirname "$0")/.."
exec node tests/harness.mjs "$@"
