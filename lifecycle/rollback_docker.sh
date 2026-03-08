#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

# Load deployment config (REGISTRY)
DEPLOY_ENV="secrets/deploy.agent_visible_env"
if [ ! -f "$DEPLOY_ENV" ]; then
    echo "ERROR: $DEPLOY_ENV not found. Copy from secrets/example.deploy.env and fill in values."
    exit 1
fi
while IFS='=' read -r key value; do
    key="${key//$'\r'/}"
    value="${value//$'\r'/}"
    [[ -z "$key" || "$key" =~ ^# ]] && continue
    export "$key"="$value"
done < "$DEPLOY_ENV"

if [ -z "${REGISTRY:-}" ]; then
    echo "ERROR: REGISTRY not set in $DEPLOY_ENV"
    exit 1
fi

IMAGE_NAME="mediamanager"
FULL_NAME="${REGISTRY}/${IMAGE_NAME}"

echo "=== Rolling back mediamanager ==="

echo "Pulling rollback tag..."
if ! docker pull "${FULL_NAME}:rollback" 2>/dev/null; then
    echo "ERROR: No rollback tag found in registry. Nothing to roll back to."
    exit 1
fi

echo "Promoting rollback to latest..."
docker tag "${FULL_NAME}:rollback" "${FULL_NAME}:latest"
docker push "${FULL_NAME}:latest"

echo ""
echo "Done! ${FULL_NAME}:rollback is now latest."
echo "Redeploy the stack in Portainer to pick up the change."
