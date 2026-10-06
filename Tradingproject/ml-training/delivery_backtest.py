"""Does NSE delivery data (share of traded volume actually taken home, not squared off intraday)
predict the stock's forward return? Source: sec_bhavdata_full files (nse_delivery_fetch.py).

Signals, each relative to the stock's OWN trailing 20-day mean (shifted one day, no look-ahead):
  rel_delper   = DELIV_PER / mean20(DELIV_PER)
  rel_delqty   = DELIV_QTY / mean20(DELIV_QTY)
  rel_trdsize  = (TTL_TRD_QNTY/NO_OF_TRADES) / mean20 of the same   (big-ticket participation)
  each also SIGNED by the day's direction (x sign(dailyReturn)) -> 'accumulation vs distribution'
Targets: forward 1/5/20-day return from the day's close. Day is the unit: per-day cross-sectional
Spearman IC across the 50 stocks, then block bootstrap over days (block = horizon). Bonferroni over all tests.
"""
import sys, os
import numpy as np, pandas as pd
from scipy import stats
sys.path.insert(0, os.path.dirname(__file__))
from literature_checks import load_daily, boot_t

CSV = sys.argv[1]
dl = pd.read_csv(CSV, parse_dates=["tradingDate"]).rename(columns={"SYMBOL": "instrument"})
daily = load_daily()[["instrument", "tradingDate", "close", "dailyReturnPct"]]
daily["instrument"] = daily["instrument"].replace({"TATAMOTORS": "TMPV"})
d = dl.merge(daily, on=["instrument", "tradingDate"], how="inner").sort_values(["instrument", "tradingDate"])
print(f"{len(d)} stock-days, {d.instrument.nunique()} stocks, {d.tradingDate.nunique()} days")
d["trdsize"] = d["TTL_TRD_QNTY"] / d["NO_OF_TRADES"]
g = d.groupby("instrument")
for src, name in (("DELIV_PER", "rel_delper"), ("DELIV_QTY", "rel_delqty"), ("trdsize", "rel_trdsize")):
    base = g[src].transform(lambda s: s.rolling(20, min_periods=15).mean().shift(1))
    d[name] = d[src] / base
    d[name + "_signed"] = d[name] * np.sign(d["dailyReturnPct"])
H = (1, 5, 20)
for h in H:
    d[f"fwd{h}"] = g["close"].transform(lambda s: s.shift(-h) / s - 1) * 100
sigs = ["rel_delper", "rel_delqty", "rel_trdsize", "rel_delper_signed", "rel_delqty_signed", "rel_trdsize_signed"]
n_tests = len(sigs) * len(H)
print(f"{n_tests} tests -> Bonferroni |t| threshold ~ {stats.norm.ppf(1 - 0.05 / (2 * n_tests)):.2f}\n")
print(f"{'signal':20s}" + "".join(f"{'fwd' + str(h) + 'd IC (t)':>22s}" for h in H) + f"{'decile spread 5d (t)':>26s}")
for s in sigs:
    cells = []
    for h in H:
        x = d.dropna(subset=[s, f"fwd{h}"])
        ic = x.groupby("tradingDate").apply(lambda q: stats.spearmanr(q[s], q[f"fwd{h}"])[0] if len(q) > 30 else np.nan)
        m, t, _ = boot_t(ic.to_numpy(), block=h)
        cells.append(f"{m:+.4f} ({t:+.2f})")
    x = d.dropna(subset=[s, "fwd5"])
    def spread(q):
        k = max(3, len(q) // 5)
        o = q.sort_values(s)
        return o.tail(k)["fwd5"].mean() - o.head(k)["fwd5"].mean()
    sp = x.groupby("tradingDate").apply(spread).to_numpy()
    m, t, _ = boot_t(sp, block=5)
    print(f"{s:20s}" + "".join(f"{c:>22s}" for c in cells) + f"{m:+.3f}% ({t:+.2f})".rjust(26))

# ---- is the signed-delivery 1-day reversal just plain 1-day reversal? control for the day's own return
print("\nControl: partial Rank IC of rel_delper_signed -> fwd1d after removing the day's own return rank, and plain reversal for reference")
x = d.dropna(subset=["rel_delper_signed", "fwd1", "dailyReturnPct"]).copy()
def partial(q):
    if len(q) < 30: return pd.Series({"plain": np.nan, "signed": np.nan, "partial": np.nan})
    r = q["dailyReturnPct"].rank(); y = q["fwd1"].rank(); s = q["rel_delper_signed"].rank()
    res = lambda a: a - np.polyval(np.polyfit(r, a, 1), r)
    return pd.Series({"plain": stats.spearmanr(q["dailyReturnPct"], q["fwd1"])[0],
                      "signed": stats.spearmanr(q["rel_delper_signed"], q["fwd1"])[0],
                      "partial": stats.spearmanr(res(s), res(y))[0]})
p = x.groupby("tradingDate").apply(partial)
for c in p: print(f"  {c:8s} mean daily IC {p[c].mean():+.4f} (t={boot_t(p[c].to_numpy())[1]:+.2f})")

print("\nEconomics of the 1-day version: each day buy bottom-quintile / sell top-quintile of rel_delper_signed, hold next close-to-close")
def ls(q):
    k = max(3, len(q) // 10); o = q.sort_values("rel_delper_signed")
    return (o.head(k)["fwd1"].mean() - o.tail(k)["fwd1"].mean()) / 2
r = x.groupby("tradingDate").apply(ls).to_numpy()
print(f"  gross {r.mean():+.3f}%/day (t={boot_t(r)[1]:+.2f}); net of 0.10% round trip: {r.mean() - 0.10:+.3f}%/day; net of 0.20%: {r.mean() - 0.20:+.3f}%/day")
