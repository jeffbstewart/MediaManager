#!/bin/bash
# Build + push the madmom sidecar image. Separate from docker-build.sh
# so server code changes don't trigger a slow rebuild of the Python
# deps every time. Run this manually when the sidecar itself changes
# (madmom-sidecar/** or proto/madmom.proto).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

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

# REGISTRY is required. NAS_IP / WATCHTOWER_PORT / WATCHTOWER_TOKEN
# are optional — if set, we ping Watchtower after push so the sidecar
# rolls automatically; if unset, the operator redeploys by hand.
for var in REGISTRY; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: $var not set in $DEPLOY_ENV"
        exit 1
    fi
done

IMAGE_NAME="mediamanager-madmom"
FULL_NAME="${REGISTRY}/${IMAGE_NAME}"
TIMESTAMP=$(date +"%Y%m%d%H%M")

echo "=== Building mediamanager-madmom (${TIMESTAMP}) ==="

# Rollback tag for symmetry with the main image.
echo "Pulling current latest for rollback tag..."
if docker pull "${FULL_NAME}:latest" 2>/dev/null; then
    docker tag "${FULL_NAME}:latest" "${FULL_NAME}:rollback"
    docker push "${FULL_NAME}:rollback"
    echo "Rollback tag updated."
else
    echo "No existing latest found — skipping rollback tag."
fi

echo ""
echo "Building ${FULL_NAME}:${TIMESTAMP}..."
# Build context is the project root so the Dockerfile can pull in
# proto/madmom.proto alongside madmom-sidecar/**.
docker build --platform linux/amd64 \
    -f madmom-sidecar/Dockerfile \
    -t "${FULL_NAME}:${TIMESTAMP}" \
    .

docker tag "${FULL_NAME}:${TIMESTAMP}" "${FULL_NAME}:latest"

echo "Pushing ${TIMESTAMP} and latest to ${REGISTRY}..."
docker push "${FULL_NAME}:${TIMESTAMP}"
docker push "${FULL_NAME}:latest"

echo ""
echo "Done! Pushed:"
echo "  ${FULL_NAME}:${TIMESTAMP}"
echo "  ${FULL_NAME}:latest"
echo "  ${FULL_NAME}:rollback (previous latest)"

# Watchtower ping — same shape as docker-build.sh. Works if the
# Watchtower service is configured to watch the mediamanager-madmom
# container (either watches all containers by default, or explicitly
# includes it in its watch-list arg). If Watchtower is scoped to
# only `mediamanager`, this endpoint call is still harmless — it
# just doesn't touch the sidecar; operator falls back to manual
# `docker-compose pull && up -d`.
if [ -n "${WATCHTOWER_TOKEN:-}" ] && [ -n "${NAS_IP:-}" ] && [ -n "${WATCHTOWER_PORT:-}" ]; then
    echo ""
    echo "Triggering Watchtower redeploy..."
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${WATCHTOWER_TOKEN}" \
        "http://${NAS_IP}:${WATCHTOWER_PORT}/v1/update" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        echo "Watchtower accepted the update request. Sidecar will restart if it's on Watchtower's watch list."
        echo "If it doesn't restart, confirm mediamanager-madmom is in the Watchtower command (e.g."
        echo "  command: --interval 300 --cleanup mediamanager mediamanager-madmom)."
    else
        echo "Warning: Watchtower returned HTTP ${HTTP_CODE}. Check NAS logs."
        echo "  Manual deploy: docker-compose pull mediamanager-madmom && docker-compose up -d"
    fi
else
    echo ""
    echo "No Watchtower vars set — skipping auto-redeploy."
    echo "Manual: docker-compose pull mediamanager-madmom && docker-compose up -d"
fi
echo ""
echo "Required one-time setup (if not done):"
echo "  - BINNACLE_API_KEY set in the docker-compose env"
echo "  - binnacle_ingest network created by the Binnacle stack"
