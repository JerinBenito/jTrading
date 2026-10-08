"""Score ONE day's same-day-close AI calls (default: today) against that day's final price for the 50 basket
stocks. Actual = the evaluated snapshot actual when the evening scoring has run, else the feed's last candle
close (clearly labelled). Usage: python scoreboard_today.py [YYYY-MM-DD]"""
import json, statistics as st, sys, urllib.parse as p, urllib.request as u
from datetime import datetime, timezone, timedelta

B = "https://jerintradingsignal.duckdns.org"
g = lambda x: json.loads(u.urlopen(B + x, timeout=120).read())
day = sys.argv[1] if len(sys.argv) > 1 else (datetime.now(timezone.utc) + timedelta(hours=5, minutes=30)).date().isoformat()
bk = {r["symbol"]: r for r in g("/api/monitor/basket") if r["symbol"] not in ("NIFTY", "BANKNIFTY")}
cal = {e["instrument"]: e for e in g("/api/results-calendar/upcoming?days=3")}
rows, ncalls = [], []
for s, b in bk.items():
    snaps = [x for x in g(f"/api/ai-predictions/{p.quote(s)}/snapshots?horizon=INTRADAY&days=5") if x["targetDate"] == day and not x["afterClose"]]
    if not snaps:
        continue
    snaps.sort(key=lambda x: x["sequenceInDay"]); ncalls.append(len(snaps))
    scored = [x["actualValue"] for x in snaps if x["actualValue"] is not None]
    actual = float(scored[0]) if scored else float(b["lastClose"])
    for lab, x in (("first", snaps[0]), ("last", snaps[-1])):
        base, pred = float(x["baselineValue"]), float(x["predictedValue"]); lo, hi = x["predictedRangeLow"], x["predictedRangeHigh"]
        rows.append(dict(s=s, lab=lab, err=abs(pred - actual) / actual * 100, nochg=abs(base - actual) / actual * 100,
                         dir=(pred > base) == (actual > base) if actual != base else None,
                         hit=(lo <= actual <= hi) if lo is not None else None, width=(hi - lo) / base * 100 if lo is not None else None,
                         move=(actual / base - 1) * 100, scored=bool(scored)))
if not rows:
    sys.exit(f"no calls found for {day}")
print(f"{day}: {len({r['s'] for r in rows})} stocks, median {st.median(ncalls):.0f} calls each | actual from evening scoring: "
      f"{sum(r['scored'] for r in rows) // 2}/{len(rows) // 2} stocks (rest use the feed's last candle)")
for lab in ("first", "last"):
    R = [r for r in rows if r["lab"] == lab]
    d = [r["dir"] for r in R if r["dir"] is not None]; h = [r["hit"] for r in R if r["hit"] is not None]
    print(f"{lab:5s} AI err {st.mean(r['err'] for r in R):.3f}% vs no-change {st.mean(r['nochg'] for r in R):.3f}% | beats baseline {100 * sum(r['err'] < r['nochg'] for r in R) / len(R):.0f}% | "
          f"direction {100 * sum(d) / len(d):.0f}% | range hit {100 * sum(h) / len(h):.0f}% | width {st.mean(r['width'] for r in R):.2f}%")
F = [r for r in rows if r["lab"] == "first"]
mv = [r["move"] for r in F]
print(f"basket move from the first call (~10:10 IST) to the close: mean {st.mean(mv):+.2f}%, median {st.median(mv):+.2f}%, down {sum(m < 0 for m in mv)}/{len(mv)}")
print("biggest movers (move from the first-call baseline):")
for r in sorted(F, key=lambda r: -abs(r["move"]))[:6]:
    print(f"  {r['s']:11s} {r['move']:+.2f}%  first-call err {r['err']:.2f}% vs no-change {r['nochg']:.2f}%  in range: {r['hit']}")
print("outside the first-call range:", [(r["s"], f"{r['move']:+.1f}%") for r in F if r["hit"] is False])
for s, e in cal.items():
    t = [(r["lab"], round(r["err"], 2), r["hit"], round(r["width"], 2)) for r in rows if r["s"] == s]
    print(f"results {e['daysUntil']:+d}d {s} ({e['resultsDate']}): (call, err%, in range, width%) {t}")
