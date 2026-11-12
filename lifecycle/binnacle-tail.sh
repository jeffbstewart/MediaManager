#!/bin/bash
# Fetch recent log records from Binnacle for a given service and pretty-print
# them. Wraps the `/api/logs/query` JSON endpoint described in CLAUDE.md.
#
# Usage:
#   lifecycle/binnacle-tail.sh                     # last 50 from mediamanager-ios
#   lifecycle/binnacle-tail.sh -n 200              # last 200
#   lifecycle/binnacle-tail.sh -s server           # short alias (server|ios|tv)
#   lifecycle/binnacle-tail.sh -s ios -l WARN      # min severity (TRACE|DEBUG|INFO|WARN|ERROR|FATAL)
#   lifecycle/binnacle-tail.sh --since 2026-05-01T00:00:00Z
#   lifecycle/binnacle-tail.sh --raw               # emit raw JSON instead of formatted lines
#
# Endpoint: set env BINNACLE_URL (e.g. https://binnacle-host:8088), or put
# a BINNACLE_URL=... line in secrets/binnacle.agent_visible_env (gitignored;
# copy from secrets/example.binnacle.env). The env var wins if both are set.
# Curl runs with -k because Binnacle uses an internal self-signed cert.

set -euo pipefail

SERVICE="mediamanager-ios"
LIMIT=50
SEVERITY=""
SINCE=""
UNTIL=""
RAW=0

# Short-name → full-service map for the three known clients.
expand_service() {
    case "$1" in
        ios)    echo "mediamanager-ios" ;;
        tv)     echo "mediamanager-android-tv" ;;
        server) echo "mediamanager-server" ;;
        *)      echo "$1" ;;
    esac
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--service)  SERVICE="$(expand_service "$2")"; shift 2 ;;
        -n|--limit)    LIMIT="$2";                       shift 2 ;;
        -l|--severity) SEVERITY="$2";                    shift 2 ;;
        --since)       SINCE="$2";                       shift 2 ;;
        --until)       UNTIL="$2";                       shift 2 ;;
        --raw)         RAW=1;                            shift   ;;
        -h|--help)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

if [ -z "${BINNACLE_URL:-}" ]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    BINNACLE_ENV="$SCRIPT_DIR/../secrets/binnacle.agent_visible_env"
    if [ -f "$BINNACLE_ENV" ]; then
        # Parse rather than source, so the file can only supply this value.
        BINNACLE_URL="$(sed -n 's/^BINNACLE_URL=//p' "$BINNACLE_ENV" | tail -n 1 | tr -d '\r"'"'"'')"
    fi
fi
if [ -z "${BINNACLE_URL:-}" ]; then
    echo "BINNACLE_URL is not set. Export it, or add BINNACLE_URL=... to" >&2
    echo "secrets/binnacle.agent_visible_env (see secrets/example.binnacle.env)." >&2
    exit 2
fi

QUERY="service=${SERVICE}&limit=${LIMIT}"
[ -n "$SEVERITY" ] && QUERY="${QUERY}&severity=${SEVERITY}"
[ -n "$SINCE" ]    && QUERY="${QUERY}&since=${SINCE}"
[ -n "$UNTIL" ]    && QUERY="${QUERY}&until=${UNTIL}"

URL="${BINNACLE_URL}/api/logs/query?${QUERY}"

# -k: Binnacle uses an internal self-signed cert; skip cert verification.
# -f: error out (non-zero exit) on HTTP errors so we don't pretty-print HTML.
RESPONSE="$(curl -sSkf --max-time 10 "$URL")" || {
    echo "Failed to query $URL" >&2
    exit 1
}

if [ "$RAW" = "1" ]; then
    echo "$RESPONSE"
    exit 0
fi

# Records come back newest-first. Reverse so the most-recent line is at the
# bottom (the natural "tail" reading order).
echo "$RESPONSE" | jq -r '
    .records | reverse | .[] |
    "\(.time) [\(.severity | ascii_upcase)] \(.service)/\(.logger // "?"): \(.message // "")"
        + (if .exception then "\n    " + (.exception | tostring | gsub("\n"; "\n    ")) else "" end)
'
