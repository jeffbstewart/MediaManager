#!/usr/bin/env bash
# Build the Angular SPA for production.
# Output goes to web-app/dist/media-manager/.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT/web-app"

echo "Building Angular SPA..."
npx ng build
echo "Build output: web-app/dist/media-manager/"
