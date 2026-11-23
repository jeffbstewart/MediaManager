#!/usr/bin/env bash
# Every string we add to an iOS String Catalog by hand has English text.
#
#   lifecycle/check-string-catalogs.sh
#
# English is the only language the app ships. Keys added by hand
# ("extractionState": "manual") are the ones Xcode turns into Swift
# symbols (STRING_CATALOG_GENERATE_SYMBOLS), so code refers to them by
# name and a missing key fails the build; this check catches the other
# half, a key whose English is missing or blank. Strings Xcode extracts
# from code are skipped: their key is the English text.
#
# Checks presence only, never wording. Exits 1 naming each bad key.
# Runs in CI (verify job); needs only jq.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

status=0
found=0
while IFS= read -r -d '' catalog; do
    found=$((found + 1))
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
            echo "${catalog#"$ROOT"/}: \"$key\" has no English text" >&2
        done <<< "$missing"
    fi
done < <(find "$ROOT/ios" -name '*.xcstrings' -not -path '*/build/*' -print0)

if [ "$found" -eq 0 ]; then
    echo "no string catalogs found under ios/" >&2
    exit 1
fi
[ "$status" -eq 0 ] && echo "string catalogs: every hand-added key has English text ($found catalog(s))"
exit "$status"
