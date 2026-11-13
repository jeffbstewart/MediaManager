"""Compact view of `idb ui describe-all` output: one line per element
with type, label, and centre point, for picking tap targets."""
import json
import sys

raw = sys.stdin.read()
try:
    nodes = json.loads(raw)
except json.JSONDecodeError:
    print("raw:", raw[:1000])
    sys.exit(1)

needle = sys.argv[1].lower() if len(sys.argv) > 1 else None
for n in nodes:
    label = str(n.get("AXLabel") or n.get("AXValue") or "")
    kind = n.get("type", "")
    f = n.get("frame", {})
    if needle and needle not in label.lower() and needle not in kind.lower():
        continue
    cx = f.get("x", 0) + f.get("width", 0) / 2
    cy = f.get("y", 0) + f.get("height", 0) / 2
    print(f"{kind:14} {label[:48]:50} centre=({cx:.0f},{cy:.0f}) size={f.get('width', 0):.0f}x{f.get('height', 0):.0f}")
