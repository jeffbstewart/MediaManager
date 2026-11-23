#!/usr/bin/env bash
# Checks the iOS String Catalogs.
#
#   lifecycle/check-string-catalogs.sh
#
# 1. Every string added by hand has English text. English is the only
#    language the app ships. Keys added by hand ("extractionState":
#    "manual") are the ones Xcode turns into Swift symbols
#    (STRING_CATALOG_GENERATE_SYMBOLS), so code refers to them by name
#    and a missing key fails the build; this catches the other half, a
#    key whose English is missing or blank.
#
# 2. Every string Xcode extracted from code has words in it. A key that
#    is only punctuation, symbols, numbers or format specifiers ("·",
#    "%lld", "%lld%%", "(%@)") means code passed something that isn't
#    text through localization: use Text(verbatim:) for separators and
#    assembled values, Text(n, format: .number) for numbers, and
#    Text(x, format: .wholePercent) for percentages (CLAUDE.md, "iOS
#    Display Strings"). Keys that need translating without words are
#    listed in ALLOWED_WITHOUT_WORDS, each with its reason.
#
# Checks presence only, never wording. Exits 1 naming each bad key.
# Runs in CI (verify job); needs only jq.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Extracted keys allowed to have no words, as a JSON array.
# - "#%@": a book's number in its series; some languages write "Nº 3".
# - "%.2g×": playback speed; decimal separators and the sign vary.
ALLOWED_WITHOUT_WORDS='["#%@", "%.2g×"]'

status=0
found=0
while IFS= read -r -d '' catalog; do
    found=$((found + 1))
    where="${catalog#"$ROOT"/}"
    missing="$(jq -r '
        .strings | to_entries[]
        | select(.value.extractionState == "manual")
        | select(
            ((.value.localizations.en.stringUnit.value // "") | test("\\S") | not)
            and (.value.localizations.en.variations == null))
        | .key' "$catalog")"
    if [ -n "$missing" ]; then
        status=1
        while IFS= read -r key; do
            echo "$where: \"$key\" has no English text" >&2
        done <<< "$missing"
    fi
    wordless="$(jq -r --argjson allowed "$ALLOWED_WITHOUT_WORDS" '
        .strings | to_entries[]
        | select(.value.extractionState != "manual" and .value.extractionState != "stale")
        | .key
        | select(. as $k | $allowed | index($k) | not)
        | select(gsub("%([0-9]+\\$)?[-+ #0-9.]*(lld|ld|lu|llu|d|u|@|f|g|e|s|i)"; "") | test("\\p{L}") | not)
        | tojson' "$catalog")"
    if [ -n "$wordless" ]; then
        status=1
        while IFS= read -r key; do
            echo "$where: extracted key $key has no words: not text, so don't localize it" >&2
        done <<< "$wordless"
    fi
done < <(find "$ROOT/ios" -name '*.xcstrings' -not -path '*/build/*' -print0)

if [ "$found" -eq 0 ]; then
    echo "no string catalogs found under ios/" >&2
    exit 1
fi
[ "$status" -eq 0 ] && echo "string catalogs: hand-added keys have English, extracted keys have words ($found catalog(s))"
exit "$status"
