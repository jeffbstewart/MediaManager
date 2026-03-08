#!/usr/bin/env bash
# presubmit-check.sh — Scan staged changes for sensitive data before commit.
#
# Usage:
#   svn diff | ./lifecycle/presubmit-check.sh        # Subversion
#   git diff --cached | ./lifecycle/presubmit-check.sh  # Git pre-commit hook
#
# Exit codes:
#   0 — clean
#   1 — violations found
#
# For Git, wire this into .git/hooks/pre-commit:
#   #!/bin/sh
#   git diff --cached | ./lifecycle/presubmit-check.sh
#
# Allowlist: lifecycle/presubmit-allowlist.txt (one pattern per line, comments with #)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALLOWLIST="$SCRIPT_DIR/presubmit-allowlist.txt"

# ---------- patterns ----------
# Banned strings are constructed via concatenation so this file itself
# never contains the literal phrases and won't trigger on its own diff.

PAT_SUBMIT="DO NOT SUB""MIT"
PAT_COMMIT="DO NOT COM""MIT"

# IPv4 addresses
PAT_IP='\b[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\b'

# UUIDs (8-4-4-4-12 hex)
PAT_UUID='\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b'

# Long hex strings (32+ hex chars — likely API keys or hashes)
PAT_HEX='\b[0-9a-fA-F]{32,}\b'

# Common API key prefixes
PAT_APIKEY='\b(sk-[a-zA-Z0-9]{20,}|Bearer [a-zA-Z0-9._-]{20,})\b'

# ---------- helpers ----------

violations=0
violation_lines=()

check_pattern() {
    local label="$1"
    local pattern="$2"
    local matches

    # Search only added lines
    local case_flag="-i"
    # Banned markers are case-sensitive (ALL CAPS only) to avoid
    # false positives from natural prose like "do not commit changes".
    [[ "$label" == "Banned marker" ]] && case_flag=""
    matches=$(echo "$DIFF_ADDED" | grep -n $case_flag -E "$pattern" 2>/dev/null || true)

    if [[ -z "$matches" ]]; then
        return
    fi

    # Filter out allowlisted patterns
    if [[ -f "$ALLOWLIST" ]]; then
        local filtered=""
        while IFS= read -r line; do
            local allowed=false
            while IFS= read -r allow_pat; do
                allow_pat="${allow_pat%$'\r'}"  # strip Windows CR
                # Skip blank lines and comments
                [[ -z "$allow_pat" || "$allow_pat" == \#* ]] && continue
                if echo "$line" | grep -qF "$allow_pat"; then
                    allowed=true
                    break
                fi
            done < "$ALLOWLIST"
            if ! $allowed; then
                filtered+="$line"$'\n'
            fi
        done <<< "$matches"
        matches="${filtered%$'\n'}"
    fi

    if [[ -n "$matches" ]]; then
        violations=$((violations + 1))
        violation_lines+=("--- $label ---")
        while IFS= read -r line; do
            [[ -n "$line" ]] && violation_lines+=("  $line")
        done <<< "$matches"
    fi
}

# ---------- main ----------

# Read diff from stdin
DIFF_INPUT=$(cat)

if [[ -z "$DIFF_INPUT" ]]; then
    echo "presubmit: no diff input (pipe svn diff or git diff --cached)"
    exit 0
fi

# Extract only added lines (+ prefix), excluding +++ file headers
DIFF_ADDED=$(echo "$DIFF_INPUT" | grep '^+' | grep -v '^+++' || true)

if [[ -z "$DIFF_ADDED" ]]; then
    echo "presubmit: no added lines to check"
    exit 0
fi

check_pattern "Banned marker" "$PAT_SUBMIT|$PAT_COMMIT"
check_pattern "IP address" "$PAT_IP"
check_pattern "UUID" "$PAT_UUID"
check_pattern "Long hex string (possible key/hash)" "$PAT_HEX"
check_pattern "API key prefix" "$PAT_APIKEY"

if [[ $violations -gt 0 ]]; then
    echo "============================================"
    echo "PRESUBMIT CHECK FAILED — $violations violation(s)"
    echo "============================================"
    for line in "${violation_lines[@]}"; do
        echo "$line"
    done
    echo ""
    echo "If any of these are intentional, add the safe value to:"
    echo "  $ALLOWLIST"
    exit 1
else
    echo "presubmit: all checks passed"

    # ---------- advisory checks (non-blocking) ----------

    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
    SCAN_SENTINEL="$PROJECT_ROOT/data/last-dep-scan"
    SCAN_MAX_AGE_DAYS=30

    if [[ -f "$SCAN_SENTINEL" ]]; then
        age_days=$(( ($(date +%s) - $(stat -c %Y "$SCAN_SENTINEL")) / 86400 ))
        if [[ $age_days -gt $SCAN_MAX_AGE_DAYS ]]; then
            echo ""
            echo "WARNING: OWASP dependency scan is overdue (${age_days} days since last run)"
            echo "  Run: ./gradlew recordDepScan"
        fi
    else
        echo ""
        echo "WARNING: OWASP dependency scan has never been run"
        echo "  Run: ./gradlew recordDepScan"
    fi

    exit 0
fi
