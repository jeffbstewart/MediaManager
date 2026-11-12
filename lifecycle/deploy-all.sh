#!/bin/bash
# Full deploy: stop buddy, build buddy + Docker image, restart buddy, sideload Roku dev channel.
# Use this after code changes that affect the buddy, server, and/or Roku channel.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
cd "$PROJECT_ROOT"

echo "=== Stopping transcode buddy ==="
bash lifecycle/stop-buddy.sh 2>/dev/null || echo "(buddy was not running)"

echo ""
echo "=== Building Docker image ==="
bash lifecycle/docker-build.sh

echo ""
echo "=== Starting transcode buddy ==="
bash lifecycle/run-buddy.sh &
BUDDY_PID=$!

echo ""
echo "=== Deploying Roku dev channel ==="
bash lifecycle/roku-deploy.sh || echo "(Roku deploy failed — device may be off or unreachable)"

echo ""
echo "=== Deploy complete ==="
echo "Buddy running as PID $BUDDY_PID"
echo "Watchtower will redeploy the server container shortly."
