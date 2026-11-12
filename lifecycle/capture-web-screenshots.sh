#!/usr/bin/env bash
# Write the documentation screenshots (docs/images/screenshots) from the
# mocked web app: tests/ci-run.sh starts the dev server, and
# tests/visual/docs.mjs drives Playwright through the pages listed in
# tests/visual/docs.spec.ts. No server, accounts, or secrets are
# involved. Every picture uses invented names and generated artwork, so
# the files can be published. See docs/SCREENSHOT_PROCEDURES.md.
#
# Prerequisite: web-app dependencies installed (npm ci under web-app/).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT/web-app"
exec tests/ci-run.sh docs "$@"
