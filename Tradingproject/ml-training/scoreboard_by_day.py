"""Per-day scoreboard of the same-day-close AI (first call and last call of each day) across the
50 basket stocks, split into the days BEFORE the volume features went live (<= 2026-09-29) and
AFTER (>= 2026-09-30). Actual close = the evaluated actual on the snapshot when scored, else the
daily close from ml_feature_snapshots (so the most recent days are still included)."""
import json
import statistics as st
import urllib.parse as p
import urllib.request as u
from collections import defaultdict

B = "https://jerintradingsignal.duckdns.org"
CUTOFF = "2026-09-30"
FROM = "2026-09-23"


def g(path):
    with u.urlopen(B + path, timeout=120) as r:
        return json.loads(r.read())


syms = [r["symbol"] for r in g("/api/monitor/basket") if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
per_day = defaultdict(lambda: {"first": [], "last": []})

for s in syms:
    snaps = [x for x in g(f"/api/ai-predictions/{p.quote(s)}/snapshots?horizon=INTRADAY&days=60")
             if x["targetDate"] >= FROM and not x["afterClose"]]
    by_date = defaultdict(list)
    for x in snaps:
        by_date[x["targetDate"]].append(x)
    for d, rows in by_date.items():
        rows.sort(key=lambda x: x["sequenceInDay"])
        scored = [x["actualValue"] for x in rows if x["actualValue"] is not None]
        if not scored:
            continue  # day not scored yet - never fall back to a half-built daily bar
        actual = float(scored[0])
        for label, x in (("first", rows[0]), ("last", rows[-1])):
            base, pred = float(x["baselineValue"]), float(x["predictedValue"])
            lo, hi = x["predictedRangeLow"], x["predictedRangeHigh"]
            rec = {
                "err": abs(pred - actual) / actual * 100,
                "base_err": abs(base - actual) / actual * 100,
                "better": abs(pred - actual) < abs(base - actual),
                "dir": ((pred - base) > 0) == ((actual - base) > 0) if actual != base and pred != base else None,
                "in": (lo is not None and float(lo) <= actual <= float(hi)),
                "has_range": lo is not None,
                "width": ((float(hi) - float(lo)) / base * 100) if lo is not None else None,
            }
            per_day[d][label].append(rec)


def summarize(recs):
    n = len(recs)
    if not n:
        return None
    dirs = [r["dir"] for r in recs if r["dir"] is not None]
    rng = [r for r in recs if r["has_range"]]
    return dict(n=n, mae=st.mean(r["err"] for r in recs), base=st.mean(r["base_err"] for r in recs),
                better=100 * sum(r["better"] for r in recs) / n,
                direction=100 * sum(dirs) / len(dirs) if dirs else float("nan"),
                cover=100 * sum(r["in"] for r in rng) / len(rng) if rng else float("nan"),
                width=st.mean(r["width"] for r in rng) if rng else float("nan"))


def row(label, s):
    return (f"  {label:12s} n={s['n']:4d}  AI err {s['mae']:.3f}% vs no-change {s['base']:.3f}%  "
            f"beats baseline {s['better']:5.1f}%  direction {s['direction']:5.1f}%  "
            f"range hit {s['cover']:5.1f}%  width {s['width']:.2f}%")


for call in ("first", "last"):
    print(f"\n===== {call.upper()} CALL of the day =====")
    pre, post = [], []
    for d in sorted(per_day):
        s = summarize(per_day[d][call])
        if not s:
            continue
        tag = "POST" if d >= CUTOFF else "pre "
        print(row(f"{d} {tag}", s))
        (post if d >= CUTOFF else pre).extend(per_day[d][call])
    print("  ---")
    print(row("PRE  (all)", summarize(pre)))
    print(row("POST (all)", summarize(post)))
