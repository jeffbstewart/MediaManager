#!/usr/bin/env bash
# Build the Angular SPA for production.
# Output goes to web-app/dist/media-manager/.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT/web-app"

echo "Building Angular SPA (production, base-href=/app/)..."
# MSYS_NO_PATHCONV prevents Git Bash from expanding /app/ as a Windows path
MSYS_NO_PATHCONV=1 npx ng build --base-href="/app/"
echo "Build output: web-app/dist/media-manager/browser/"
