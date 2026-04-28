#!/usr/bin/env bash
# presubmit-check.sh — Scan staged changes for sensitive data before commit.
#
# Usage:
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
# Allowlist: lifecycle/presubmit-allowlist.txt
#   - Substring match:  172.16.4.12      (lines containing this string are skipped)
#   - File-level skip:  file:hls.min.js  (all changes in matching files are skipped)

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

# Third-party host references — the SPA must never make a browser
# request to any of these. Server-side code legitimately fetches
# from them via ImageProxyService; only flag when added to the
# web-app/ tree. Constructed via concatenation so the literal
# strings don't appear in this file's own diff.
PAT_THIRDPARTY_HOST='\b(image\.tmdb'\
'\.org|coverartarchive'\
'\.org|covers\.openlibrary'\
'\.org|commons\.wikimedia'\
'\.org|fonts\.googleapis'\
'\.com|fonts\.gstatic'\
'\.com|cdn\.jsdelivr'\
'\.net|cdnjs\.cloudflare'\
'\.com|unpkg'\
'\.com|gravatar'\
'\.com|google-analytics'\
'\.com|googletagmanager'\
'\.com)\b'

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

# ---------- banned file extensions ----------
# Files that must never be committed (signing certs, provisioning profiles)
BANNED_EXTENSIONS=("*.p12" "*.mobileprovision" "*.cer" "*.keystore" "*.jks")

# ---------- main ----------

# Read diff from stdin
DIFF_INPUT=$(cat)

if [[ -z "$DIFF_INPUT" ]]; then
    echo "presubmit: no diff input (pipe git diff --cached)"
    exit 0
fi

# ---------- banned file extension check ----------
# Check if any staged files match banned extensions (signing certs, etc.)
banned_files=()
while IFS= read -r line; do
    if [[ "$line" == "diff --git "* ]]; then
        file="${line##* b/}"
        for ext_pattern in "${BANNED_EXTENSIONS[@]}"; do
            ext="${ext_pattern#\*}"  # strip leading * to get .p12, .mobileprovision, etc.
            if [[ "$file" == *"$ext" ]]; then
                banned_files+=("$file")
                break
            fi
        done
    fi
done <<< "$DIFF_INPUT"

if [[ ${#banned_files[@]} -gt 0 ]]; then
    echo "============================================"
    echo "PRESUBMIT CHECK FAILED — banned file type(s)"
    echo "============================================"
    echo "  The following files must not be committed"
    echo "  (signing certificates, provisioning profiles):"
    for f in "${banned_files[@]}"; do
        echo "    $f"
    done
    echo ""
    echo "  Add these extensions to .gitignore instead."
    exit 1
fi

# ---------- generated-code paths check ----------
# Anything under one of these prefixes is auto-generated from a single
# source-of-truth and must never be committed; the codegen runs
# transitively from the build (Gradle :generateWebProto, npm
# postinstall) so a fresh checkout always has it.
GENERATED_PATHS=(
    "web-app/src/app/proto-gen/"
)
generated_files=()
while IFS= read -r line; do
    if [[ "$line" == "diff --git "* ]]; then
        file="${line##* b/}"
        for prefix in "${GENERATED_PATHS[@]}"; do
            if [[ "$file" == "$prefix"* ]]; then
                generated_files+=("$file")
                break
            fi
        done
    fi
done <<< "$DIFF_INPUT"

if [[ ${#generated_files[@]} -gt 0 ]]; then
    echo "================================================"
    echo "PRESUBMIT CHECK FAILED — generated code staged"
    echo "================================================"
    echo "  These paths are .gitignored and regenerated by"
    echo "  the build — never commit them:"
    for f in "${generated_files[@]}"; do
        echo "    $f"
    done
    echo ""
    echo "  Run \`git restore --staged <file>\` to unstage,"
    echo "  then re-run codegen via \`./gradlew generateWebProto\`."
    exit 1
fi

# Build list of file-level allowlist patterns (file:xxx entries)
ALLOWED_FILES=()
if [[ -f "$ALLOWLIST" ]]; then
    while IFS= read -r pat; do
        pat="${pat%$'\r'}"
        [[ -z "$pat" || "$pat" == \#* ]] && continue
        if [[ "$pat" == file:* ]]; then
            ALLOWED_FILES+=("${pat#file:}")
        fi
    done < "$ALLOWLIST"
fi

# Extract added lines with file context, skipping allowlisted files.
# Output format: each line is the raw "+..." content from the diff.
DIFF_ADDED=""
current_file=""
skip_file=false
while IFS= read -r line; do
    # Track which file we're in via "diff --git a/... b/..." headers
    if [[ "$line" == "diff --git "* ]]; then
        # Extract b/path (the destination file)
        current_file="${line##* b/}"
        skip_file=false
        for fpat in "${ALLOWED_FILES[@]+"${ALLOWED_FILES[@]}"}"; do
            if [[ "$current_file" == *"$fpat"* ]]; then
                skip_file=true
                break
            fi
        done
        continue
    fi
    # Only look at added lines, skip +++ headers
    if $skip_file; then
        continue
    fi
    if [[ "$line" == +* && "$line" != "+++"* ]]; then
        DIFF_ADDED+="$line"$'\n'
    fi
done <<< "$DIFF_INPUT"

if [[ -z "$DIFF_ADDED" ]]; then
    echo "presubmit: no added lines to check"
    exit 0
fi

check_pattern "Banned marker" "$PAT_SUBMIT|$PAT_COMMIT"
check_pattern "IP address" "$PAT_IP"
check_pattern "UUID" "$PAT_UUID"
check_pattern "Long hex string (possible key/hash)" "$PAT_HEX"
check_pattern "API key prefix" "$PAT_APIKEY"

# ---------- third-party host references in the SPA ----------
# Server-side code legitimately fetches from third-party image hosts
# via ImageProxyService. The SPA must never reference them — every
# browser request stays on our origin. Walk the diff again, but only
# inspect added lines under web-app/src/.
spa_host_violations=()
current_file=""
spa_in_scope=false
spa_in_test=false
while IFS= read -r line; do
    if [[ "$line" == "diff --git "* ]]; then
        current_file="${line##* b/}"
        spa_in_scope=false
        spa_in_test=false
        if [[ "$current_file" == web-app/src/* || "$current_file" == web-app/projects/*/src/* ]]; then
            spa_in_scope=true
        fi
        # Tests, comments, audit docs, and the regression spec itself
        # legitimately reference these hosts — the rule is about runtime
        # SPA code, not test fixtures or descriptive prose.
        if [[ "$current_file" == web-app/tests/* || "$current_file" == web-app/src/test/* ]]; then
            spa_in_test=true
        fi
        continue
    fi
    if ! $spa_in_scope || $spa_in_test; then
        continue
    fi
    if [[ "$line" == +* && "$line" != "+++"* ]]; then
        if echo "$line" | grep -qE "$PAT_THIRDPARTY_HOST"; then
            spa_host_violations+=("  $current_file: ${line:1}")
        fi
    fi
done <<< "$DIFF_INPUT"

if [[ ${#spa_host_violations[@]} -gt 0 ]]; then
    violations=$((violations + 1))
    violation_lines+=("--- Third-party host in SPA source ---")
    for entry in "${spa_host_violations[@]}"; do
        violation_lines+=("$entry")
    done
    violation_lines+=("  (SPA browsers must only contact our origin — see")
    violation_lines+=("   docs/THIRD_PARTY_AUDIT.md for the rule and exceptions.)")
fi

# Personal patterns (gitignored, per-developer)
PERSONAL_PATTERNS="$SCRIPT_DIR/presubmit-personal-patterns.txt"
if [[ -f "$PERSONAL_PATTERNS" ]]; then
    while IFS= read -r pat; do
        pat="${pat%$'\r'}"
        [[ -z "$pat" || "$pat" == \#* ]] && continue
        check_pattern "Personal pattern" "$pat"
    done < "$PERSONAL_PATTERNS"
fi

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
        if [[ "$OSTYPE" == darwin* ]]; then
            file_mtime=$(stat -f %m "$SCAN_SENTINEL")
        else
            file_mtime=$(stat -c %Y "$SCAN_SENTINEL")
        fi
        age_days=$(( ($(date +%s) - file_mtime) / 86400 ))
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
