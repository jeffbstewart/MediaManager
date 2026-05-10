#!/usr/bin/env bash
# Capture documentation screenshots against the App Store demo
# server. Sources app_store_demo_setup/secrets/.env, exports the
# expected DEMO_* env vars, then drives Playwright via the script
# at web-app/tests/scripts/capture-docs-screenshots.mjs.
#
# Output: PNG files written into docs/images/screenshots/
# (overwriting whatever was there). Stateful screenshots (player,
# wishlist with mixed statuses, purchase-wishes with mixed
# statuses) are skipped — capture those interactively via the
# Playwright MCP.
#
# Prerequisites:
#   - secrets/.env populated (./app_store_demo_setup/secrets/.env)
#   - The 6 demo accounts exist (run `seed-users` first)
#   - Demo server reachable at $DEMO_BASE_URL
#   - web-app deps installed (npm ci under web-app/ if not)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/app_store_demo_setup/secrets/.env"

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: $ENV_FILE not found. Copy app_store_demo_setup/secrets/example.env to .env and fill it in." >&2
    exit 1
fi

# Source the .env. Lines are KEY=VALUE; export each.
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

cd "$PROJECT_ROOT/web-app"
node tests/scripts/capture-docs-screenshots.mjs "$@"
