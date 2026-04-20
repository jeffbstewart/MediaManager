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
echo ""
echo "Next steps:"
echo "  - Ensure BINNACLE_API_KEY is set in your docker-compose env."
echo "  - Ensure binnacle_ingest network exists (created by Binnacle stack)."
echo "  - docker-compose pull mediamanager-madmom && docker-compose up -d"
