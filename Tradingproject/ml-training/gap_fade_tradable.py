"""Tradable version of the gap-fade finding. The signal (overnight gap, prev close -> open) is known
at 09:15; we enter at the CLOSE OF THE FIRST HOURLY CANDLE (10:15, price = currentPrice at
hoursSinceOpen=0) and exit at the day's close, so the first hour's reversal is NOT counted.
Each day: short the top-k gap-up stocks, long the bottom-k gap-down stocks (equal weight, k=5 or 10),
return per day = mean(long leg) - mean(short leg) split into legs. Day is the unit; block bootstrap.
Also shown: entering at the open (not tradable, upper bound), split-half stability, cost sensitivity.
"""
import json, os, sys, tempfile
import numpy as np, pandas as pd
from scipy import stats
sys.path.insert(0, os.path.dirname(__file__))
from literature_checks import load_daily, boot_t, CACHE

daily = load_daily()[["instrument", "tradingDate", "gapFromPrevClosePct", "open", "close"]]
it = pd.DataFrame(json.load(open(CACHE, encoding="utf-8")))
it["tradingDate"] = pd.to_datetime(it["tradingDate"])
h0 = it[(it.hoursSinceOpen == 0) & ~it.instrument.isin(["NIFTY", "BANKNIFTY"])][
    ["instrument", "tradingDate", "returnSoFarPct", "remainingDriftPct"]]
d = h0.merge(daily, on=["instrument", "tradingDate"]).dropna()
d["open_to_close"] = (d["close"] / d["open"] - 1) * 100
print(f"{len(d)} stock-days, {d.tradingDate.nunique()} days, {d.tradingDate.min().date()}..{d.tradingDate.max().date()}")

# the first hour already eats part of the reversal
print("\nmean first-hour move (open->10:15) by gap bucket, then 10:15->close move:")
for lab, m in (("gap > +1%", d.gapFromPrevClosePct > 1), ("gap < -1%", d.gapFromPrevClosePct < -1),
               ("|gap| < 0.3%", d.gapFromPrevClosePct.abs() < 0.3)):
    g = d[m]
    print(f"  {lab:12s} n={len(g):5d}  first hour {g.returnSoFarPct.mean():+.3f}%   10:15->close {g.remainingDriftPct.mean():+.3f}%")

ic = d.groupby("tradingDate").apply(lambda g: stats.spearmanr(g.gapFromPrevClosePct, g.remainingDriftPct)[0] if len(g) > 30 else np.nan)
m, t, n = boot_t(ic.to_numpy())
print(f"\nRank IC gap -> 10:15-to-close return: {m:+.4f} (t={t:+.2f}, {n} days)")


def daily_ls(g, k, col):
    s = g.sort_values("gapFromPrevClosePct")
    long_ = s.head(k)[col].mean()      # biggest gap-downs: bought
    short_ = -s.tail(k)[col].mean()    # biggest gap-ups: shorted
    return pd.Series({"long": long_, "short": short_, "ls": (long_ + short_) / 2})  # 1 unit capital split across 2 legs


for k in (5, 10):
    for col, lab in (("remainingDriftPct", "TRADABLE enter 10:15"), ("open_to_close", "upper bound enter at open")):
        r = d.groupby("tradingDate").apply(lambda g: daily_ls(g, k, col))
        print(f"\n--- top/bottom {k} by gap, {lab} ---")
        for c in ("long", "short", "ls"):
            mm, tt, nn = boot_t(r[c].to_numpy())
            print(f"  {c:5s} mean {mm:+.3f}%/day (t={tt:+.2f})  hit-rate {100 * (r[c] > 0).mean():.1f}%  n={nn}")
        if col == "remainingDriftPct":
            ls = r["ls"].to_numpy(); half = len(ls) // 2
            print(f"  stability: first half {ls[:half].mean():+.3f}%  second half {ls[half:].mean():+.3f}%")
            for q, lo in enumerate(np.array_split(np.arange(len(ls)), 4), 1):
                print(f"    quarter {q}: {ls[lo].mean():+.3f}%")
            # costs: every day = 2 legs each a round trip on half the capital => cost per capital = rt cost
            for rt in (0.05, 0.10, 0.15, 0.20):
                net = ls - rt
                print(f"  net of {rt:.2f}% round-trip cost: {net.mean():+.3f}%/day = ~{net.mean() * 250:+.0f}%/yr gross-capital, "
                      f"t={boot_t(net)[1]:+.2f}")
