"""Results-day event study on the 50-stock basket (dates from earnings_fetch.py).
A. Validation: do the Yahoo dates line up with real volume/price spikes?  (if not, the data is useless)
B. Magnitude: how much bigger is the move around results (relevant to the model's range bands)?
C. Direction: does the EPS surprise (or the reaction) predict drift after results (PEAD)?  pre-results run-up?
Indian companies report during or after the session, so the reaction window is close(E-1) -> close(E+1).
"""
import sys, os
import numpy as np, pandas as pd
from scipy import stats
sys.path.insert(0, os.path.dirname(__file__))
from literature_checks import load_daily, boot_t

ev = pd.read_csv(sys.argv[1])
ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10])
ev = ev.dropna(subset=["Reported EPS"])
daily = load_daily()
daily["instrument"] = daily["instrument"].replace({"TATAMOTORS": "TMPV"})
for c in ("volumeRatio20d", "dailyReturnPct", "high", "low", "open", "close"):
    daily[c] = pd.to_numeric(daily[c], errors="coerce")
daily["range_pct"] = (daily["high"] - daily["low"]) / daily["open"] * 100
dmin, dmax = daily.tradingDate.min(), daily.tradingDate.max()
ev = ev[(ev.date >= dmin + pd.Timedelta(days=30)) & (ev.date <= dmax - pd.Timedelta(days=35))]
print(f"{len(ev)} past results events with reported EPS in {dmin.date()}..{dmax.date()} across {ev.instrument.nunique()} stocks")

by = {s: g.reset_index(drop=True) for s, g in daily.groupby("instrument")}
base_abs = daily["dailyReturnPct"].abs().mean(); base_vol = daily["volumeRatio20d"].mean(); base_rng = daily["range_pct"].mean()
rows = []
for _, e in ev.iterrows():
    g = by.get(e.instrument)
    if g is None: continue
    idx = g.index[g.tradingDate >= e.date]
    if len(idx) == 0 or g.loc[idx[0], "tradingDate"] - e.date > pd.Timedelta(days=4): continue
    i = idx[0]
    if i < 7 or i + 21 >= len(g): continue
    r = {"instrument": e.instrument, "date": e.date, "surprise": e["Surprise(%)"], "q": e.date.to_period("Q")}
    for o in range(-2, 4):
        r[f"abs{o}"] = abs(g.loc[i + o, "dailyReturnPct"]); r[f"vol{o}"] = g.loc[i + o, "volumeRatio20d"]; r[f"rng{o}"] = g.loc[i + o, "range_pct"]
    c = g["close"]
    r["react"] = (c[i + 1] / c[i - 1] - 1) * 100
    r["pre"] = (c[i - 1] / c[i - 6] - 1) * 100
    for h in (1, 5, 20):
        r[f"post{h}"] = (c[i + 1 + h] / c[i + 1] - 1) * 100 if i + 1 + h < len(g) else np.nan
    rows.append(r)
d = pd.DataFrame(rows)
print(f"matched {len(d)} events to price data\n")

print("A/B. around the results date (offset 0 = first trading day on/after the date); baseline = all stock-days")
print(f"  baseline: |ret| {base_abs:.2f}%  volume ratio {base_vol:.2f}  day range {base_rng:.2f}%")
print(f"  {'offset':>6s} {'|ret|%':>8s} {'x base':>7s} {'vol ratio':>10s} {'range%':>8s}")
for o in range(-2, 4):
    print(f"  {o:6d} {d[f'abs{o}'].mean():8.2f} {d[f'abs{o}'].mean() / base_abs:7.2f} {d[f'vol{o}'].mean():10.2f} {d[f'rng{o}'].mean():8.2f}")
peak = max(range(0, 2), key=lambda o: d[f"vol{o}"].mean())
print(f"  -> reaction mostly on offset {peak}; ({100 * (d['vol1'] > d['vol0']).mean():.0f}% of events have bigger volume on offset +1 than 0, i.e. after-close announcements)")

print("\nC. direction. Spearman correlation (permutation p-value) and mean returns by surprise tercile")
def perm_p(x, y, n=5000, seed=0):
    rng = np.random.default_rng(seed); r0 = stats.spearmanr(x, y)[0]
    null = np.array([stats.spearmanr(x, rng.permutation(y))[0] for _ in range(n)])
    return r0, (np.abs(null) >= abs(r0)).mean()
dd = d.dropna(subset=["surprise"]).copy()
dd["surprise_c"] = dd["surprise"].clip(-50, 50)
print(f"  {len(dd)} events with a surprise value")
for tgt, lab in (("react", "reaction  (E-1 -> E+1 close)"), ("post1", "next 1d after reaction"), ("post5", "next 5d after reaction (PEAD)"),
                 ("post20", "next 20d after reaction (PEAD)")):
    x = dd.dropna(subset=[tgt]); r0, p = perm_p(x["surprise_c"], x[tgt])
    print(f"  EPS surprise -> {lab:34s} rho={r0:+.3f} perm p={p:.3f} (n={len(x)})")
dd["terc"] = pd.qcut(dd["surprise_c"], 3, labels=["worst", "mid", "best"])
print("  mean returns by surprise tercile:")
print(dd.groupby("terc", observed=True)[["react", "post1", "post5", "post20"]].mean().round(3).to_string())
print("  reaction itself as drift signal (continuation vs reversal):")
for tgt in ("post5", "post20"):
    x = d.dropna(subset=[tgt]); r0, p = perm_p(x["react"], x[tgt])
    print(f"  reaction -> {tgt}: rho={r0:+.3f} perm p={p:.3f} (n={len(x)})")
m, t, n = boot_t(d["pre"].to_numpy()); print(f"\n  pre-results run-up (E-6 -> E-1): mean {m:+.3f}% (t={t:+.2f}, n={n})  [uncorrelated events assumed]")
# calendar clustering check: results cluster in a few weeks per quarter -> condition on the market
print("  events per calendar quarter:", d.groupby("q").size().to_dict())
