#!/usr/bin/env bash
# Drive the iOS app in the booted simulator over idb (Meta's iOS
# Development Bridge: `brew tap facebook/fb && brew install
# idb-companion && pipx install fb-idb`). Lets an agent tap, scroll,
# type and screenshot without a human at the Simulator window.
#
#   lifecycle/sim-drive.sh tree [filter]      accessibility tree: type, label, centre point
#   lifecycle/sim-drive.sh tap-label "Books"  tap by label (exact match first, then substring)
#   lifecycle/sim-drive.sh tap X Y            tap a point
#   lifecycle/sim-drive.sh swipe-up [amount]  scroll content (also swipe-down)
#   lifecycle/sim-drive.sh back               tap the navigation bar's back button
#   lifecycle/sim-drive.sh goto "Music"       back out to the sidebar top, then tap the label
#   lifecycle/sim-drive.sh shot NAME          screenshot to data/screenshots/NAME.png
#
# Uses the first booted simulator unless SIM_UDID is set. Stay out of
# the server administration screens when driving a real session.
set -euo pipefail
export PATH="$HOME/.local/bin:/opt/homebrew/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$ROOT/lifecycle"
UDID="${SIM_UDID:-$(xcrun simctl list devices booted -j | jq -r '.devices[][] | select(.state == "Booted") | .udid' | head -1)}"
if [ -z "$UDID" ]; then echo "no booted simulator" >&2; exit 1; fi

tree() { idb ui describe-all --udid "$UDID" 2>/dev/null | python3 -I "$S/sim-ax.py" "${1:-}"; }

case "${1:-}" in
  tree) tree "${2:-}" ;;
  tap) idb ui tap --udid "$UDID" "$2" "$3" >/dev/null ;;
  tap-label)
    # Prefer an exact label match (first column after the type), then
    # fall back to the first substring match. Admin rows share words
    # with viewer rows ("Cameras", "Tags"), so exact wins.
    line=$(tree "$2" | grep -v '^Application' | awk -v want="$2" '{ lbl=$0; sub(/^[A-Za-z]+ +/, "", lbl); sub(/ +centre=.*/, "", lbl); if (lbl == want) { print; exit } }')
    if [ -z "$line" ]; then line=$(tree "$2" | grep -v '^Application' | head -1); fi
    if [ -z "$line" ]; then echo "no element matching: $2" >&2; exit 1; fi
    xy=$(echo "$line" | sed -E 's/.*centre=\(([0-9]+),([0-9]+)\).*/\1 \2/')
    echo "tap $line"
    # shellcheck disable=SC2086
    idb ui tap --udid "$UDID" $xy >/dev/null ;;
  swipe-up)
    # Start above the bottom search bar (y≈890) so the drag scrolls
    # content instead of grabbing the field; drag to y=200.
    amt=${2:-500}; from=$((200 + amt)); [ "$from" -gt 820 ] && from=820
    idb ui swipe --udid "$UDID" --duration 0.3 220 $from 220 200 >/dev/null ;;
  swipe-down)
    amt=${2:-500}; to=$((200 + amt)); [ "$to" -gt 820 ] && to=820
    idb ui swipe --udid "$UDID" --duration 0.3 220 200 220 $to >/dev/null ;;
  back) idb ui tap --udid "$UDID" 42 84 >/dev/null ;;
  goto)
    # Back out to the sidebar, scroll it to the top, then tap the label.
    for _ in 1 2 3 4 5; do
      t=$(tree)
      if echo "$t" | grep -q 'Offline Mode'; then break; fi
      # The back button is labelled with the previous page's title, so
      # find it by its position in the navigation bar.
      if echo "$t" | grep -q '^Button .*centre=(42,84)'; then idb ui tap --udid "$UDID" 42 84 >/dev/null; sleep 1.2; continue; fi
      # An active search bar hides the navigation bar; dismiss it first.
      # `|| true`: under pipefail a no-match grep would abort the script.
      closexy=$(echo "$t" | grep -E '^Button +(close|Cancel) ' | head -1 | sed -E 's/.*centre=\(([0-9]+),([0-9]+)\).*/\1 \2/' || true)
      if [ -n "$closexy" ]; then idb ui tap --udid "$UDID" $closexy >/dev/null; sleep 1.2; continue; fi
      # After a search session the bar is reported as an opaque group;
      # tap the back position blindly, then scroll the sidebar to top.
      idb ui tap --udid "$UDID" 42 84 >/dev/null; sleep 1.2
      idb ui swipe --udid "$UDID" --duration 0.3 220 200 220 820 >/dev/null; sleep 1
    done
    "$0" tap-label "$2" ;;
  shot) sleep 1; xcrun simctl io "$UDID" screenshot "$ROOT/data/screenshots/$2.png" >/dev/null 2>&1; echo "$ROOT/data/screenshots/$2.png" ;;
  *) echo "unknown command" >&2; exit 1 ;;
esac
