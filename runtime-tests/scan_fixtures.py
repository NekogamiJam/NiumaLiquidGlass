"""One-off fixture scan for the Z-tap / period behavior probes (not a shipped test)."""
from __future__ import annotations
import collections
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "input-data" / "niumawb_zc.dict.yaml"
lines = p.read_text(encoding="utf-8-sig").splitlines()
end = next(i for i, s in enumerate(lines) if s.strip() == "...")
cols: list[str] = []
in_cols = False
for s in lines[:end]:
    if s.lstrip().startswith("#"):
        continue
    if s.strip() == "columns:":
        in_cols = True
        continue
    if in_cols:
        if s.strip().startswith("- "):
            cols.append(s.strip()[2:].split("#", 1)[0].strip())
        elif s and not s[0].isspace():
            in_cols = False
ci = cols.index("code")
cnt: dict[str, int] = collections.Counter()
rows: dict[str, list[str]] = collections.defaultdict(list)
for line in lines[end + 1:]:
    if not line or line.startswith("#"):
        continue
    f = line.split("\t")
    if len(f) <= max(ci, cols.index("text")):
        continue
    rows[f[ci]].append(f[cols.index("text")])
    cnt[f[ci]] += 1

rich = [c for c in cnt if len(c) == 4 and c[0] != "z" and cnt[c] >= 10]
rich.sort(key=lambda c: (-cnt[c], c))
print("4-letter zc codes with >=10 candidates (top 10):")
for c in rich[:10]:
    print("  ", c, cnt[c], rows[c][:4])
few = sorted(c for c in cnt if len(c) == 4 and c[0] != "z" and cnt[c] in (1, 2))
print("4-letter zc codes with 1-2 candidates:", few[:12])
print("aaaa:", cnt.get("aaaa"), rows["aaaa"][:4])
print("khlg:", cnt.get("khlg"), rows.get("khlg"))
print("zbd:", cnt.get("zbd"), rows.get("zbd", [])[:4])
