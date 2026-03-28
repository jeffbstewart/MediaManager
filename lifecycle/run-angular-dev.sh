#!/usr/bin/env bash
# Start the Angular dev server with API proxy pointed at the Armeria server.
# Reads HAPROXY_URL from secrets/deploy.agent_visible_env.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/secrets/deploy.agent_visible_env"

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: $ENV_FILE not found"
    echo "Copy secrets/example.deploy.env to secrets/deploy.agent_visible_env and fill in values."
    exit 1
fi

source "$ENV_FILE"

if [ -z "$HAPROXY_URL" ]; then
    echo "ERROR: HAPROXY_URL not set in $ENV_FILE"
    exit 1
fi

export MM_API_TARGET="$HAPROXY_URL"
echo "Angular dev server proxying API to: $MM_API_TARGET"

cd "$PROJECT_ROOT/web-app"
npx ng serve --proxy-config proxy.conf.js
