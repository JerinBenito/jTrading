"""Commodity futures: what can honestly be said about "it is up X% over the last month - how high can it still go (the peak) and where
does it end up?"  26 years of daily continuous-futures history (Yahoo: gold, silver, crude, Brent, natgas, copper) - the same
percentage dynamics drive the MCX contracts (INR price = USD price x USDINR x local premium).

Two separate questions, tested walk-forward (every forecast uses only data before the day):
  1. RANGE: given today's volatility (EWMA), how high does the price peak / how low does it trough within the next H trading days, and
     how far from the peak does it close?  Calibration of vol-scaled quantiles, expanding window, scored on the last 10 years.
  2. DIRECTION: after an up-move of L days, is the next H-day return or peak different from normal (momentum / reversal)?  Bucketed
     by the trailing return's z-score; non-overlapping samples so the t-statistics are honest.
Usage: python commodity_research.py
"""
import os
import numpy as np, pandas as pd
from scipy import stats

HERE = os.path.dirname(os.path.abspath(__file__))
SYMS = {"GC_F": "Gold", "SI_F": "Silver", "CL_F": "Crude WTI", "BZ_F": "Brent", "NG_F": "Natural gas", "HG_F": "Copper"}
HORIZONS = [5, 10, 21, 42]
TRAIL = 21


def load(sym):
    d = pd.read_csv(os.path.join(HERE, "data", "comm", f"{sym}.csv"), parse_dates=["date"]).sort_values("date").reset_index(drop=True)
    d = d[(d.close > 0) & (d.high > 0) & (d.low > 0)]
    if sym == "CL_F":                                   # WTI went negative on 2020-04-20: drop the month around it
        d = d[~((d.date > "2020-03-25") & (d.date < "2020-05-20"))]
    d = d.reset_index(drop=True)
    d["ret"] = np.log(d.close).diff()
    d = d[d.ret.abs() < 0.35].reset_index(drop=True)   # a >35% daily move in these series is a data/roll glitch
    d["ret"] = np.log(d.close).diff()
    lam = 0.94
    d["sig"] = np.sqrt((d.ret ** 2).ewm(alpha=1 - lam, adjust=False).mean().shift(0))
    d["trail"] = np.log(d.close / d.close.shift(TRAIL))
    d["trail_z"] = d.trail / (d.sig * np.sqrt(TRAIL))
    return d


def forward(d, H):
    c = d.close.to_numpy(); hi = d.high.to_numpy(); lo = d.low.to_numpy(); n = len(d)
    peak = np.full(n, np.nan); trough = np.full(n, np.nan); fin = np.full(n, np.nan); tpk = np.full(n, np.nan)
    for i in range(n - H):
        w_hi = hi[i + 1:i + H + 1]; w_lo = lo[i + 1:i + H + 1]
        peak[i] = np.log(max(w_hi.max(), c[i]) / c[i]); trough[i] = np.log(min(w_lo.min(), c[i]) / c[i])
        fin[i] = np.log(c[i + H] / c[i]); tpk[i] = int(np.argmax(w_hi)) + 1
    d[f"peak{H}"] = peak; d[f"trough{H}"] = trough; d[f"fin{H}"] = fin; d[f"tpk{H}"] = tpk


all_rows = []
for sym, name in SYMS.items():
    d = load(sym)
    for H in HORIZONS:
        forward(d, H)
    d["sym"] = sym; d["name"] = name
    all_rows.append(d)
D = pd.concat(all_rows, ignore_index=True)
D = D.dropna(subset=["sig", "trail_z"]); D = D[D.date >= "2001-01-01"]
TEST_FROM = pd.Timestamp(D.date.max().year - 10, 1, 1)
print(f"{len(D):,} commodity-days, {D.date.min().date()}..{D.date.max().date()}, out-of-sample from {TEST_FROM.date()}")

# ---------- 1. RANGE: calibration of vol-scaled peak / trough quantiles (walk-forward, expanding window, quantiles re-estimated yearly)
QS = [0.5, 0.75, 0.9]
print("\n=== 1. How high can it peak / how deep can it dip within H trading days? (out-of-sample coverage; target = nominal) ===")
print(f"{'horizon':8s}{'what':8s}" + "".join(f"  q{int(q * 100)} cover" for q in QS) + "   median peak as multiple of 1-sigma*sqrt(H)")
for H in HORIZONS:
    for what in ("peak", "trough"):
        hits = {q: [] for q in QS}; med = []
        for yr in range(TEST_FROM.year, D.date.max().year + 1):
            tr = D[(D.date < pd.Timestamp(yr, 1, 1) - pd.Timedelta(days=H * 2)) & D[f"{what}{H}"].notna()]
            te = D[(D.date.dt.year == yr) & D[f"{what}{H}"].notna()]
            if te.empty:
                continue
            sgn = 1 if what == "peak" else -1
            scaled_tr = sgn * tr[f"{what}{H}"] / (tr.sig * np.sqrt(H))
            for q in QS:
                thr = np.quantile(scaled_tr, q)
                hits[q] += list((sgn * te[f"{what}{H}"] / (te.sig * np.sqrt(H)) <= thr).astype(int))
            med.append(np.quantile(scaled_tr, 0.5))
        print(f"{H:>3d} days {what:8s}" + "".join(f"  {100 * np.mean(hits[q]):6.1f}%  " for q in QS) + f"   {np.mean(med):.2f}")

# per-commodity coverage for q90 at 21 days (is the same scaling right for every commodity?)
print("\nq90 coverage by commodity (21-day peak / trough), out-of-sample:")
for sym, name in SYMS.items():
    row = [name.ljust(12)]
    for what in ("peak", "trough"):
        H = 21; hits = []
        for yr in range(TEST_FROM.year, D.date.max().year + 1):
            tr = D[(D.date < pd.Timestamp(yr, 1, 1) - pd.Timedelta(days=42)) & D[f"{what}{H}"].notna()]
            te = D[(D.date.dt.year == yr) & (D.sym == sym) & D[f"{what}{H}"].notna()]
            if te.empty:
                continue
            sg = 1 if what == "peak" else -1
            thr = np.quantile(sg * tr[f"{what}{H}"] / (tr.sig * np.sqrt(H)), 0.9)
            hits += list((sg * te[f"{what}{H}"] / (te.sig * np.sqrt(H)) <= thr).astype(int))
        row.append(f"{what} {100 * np.mean(hits):.1f}%")
    print("   " + "   ".join(row))

# ---------- 2. DIRECTION: after a strong trailing move, does the next H days look different?
print("\n=== 2. After a strong trailing move (last 21 days), what happens next? (non-overlapping, all commodities pooled) ===")
D["bucket"] = pd.cut(D.trail_z, [-99, -1.5, -0.5, 0.5, 1.5, 99], labels=["strong down", "down", "flat", "up", "strong up"])
for H in (10, 21, 42):
    print(f"\n  next {H} trading days, z = trailing return / (daily vol * sqrt(21)):")
    print(f"  {'trailing move':14s}{'n(indep)':>9s}{'mean fwd ret':>14s}{'t':>7s}{'P(up)':>8s}{'median peak':>13s}{'median dip':>12s}")
    sub = D[D[f"fin{H}"].notna()]
    for b in ["strong down", "down", "flat", "up", "strong up"]:
        g = sub[sub.bucket == b]
        # non-overlapping: keep every H-th row per commodity
        g = pd.concat([x.iloc[::H] for _, x in g.groupby("sym")])
        r = g[f"fin{H}"] / (g.sig * np.sqrt(H))
        t = r.mean() / (r.std(ddof=1) / np.sqrt(len(r)))
        print(f"  {b:14s}{len(g):9d}{100 * g[f'fin{H}'].mean():13.2f}%{t:+7.2f}{100 * (g[f'fin{H}'] > 0).mean():7.1f}%{100 * g[f'peak{H}'].median():12.2f}%{100 * g[f'trough{H}'].median():11.2f}%")

# by decade for the key hypothesis (strong up -> next 21d)
print("\n  'strong up' -> next-21-day return, by period (mean in daily-vol units; t):")
g = D[(D.bucket == "strong up") & D.fin21.notna()]
for lo, hi in [(2001, 2009), (2010, 2017), (2018, 2022), (2023, 2027)]:
    x = g[(g.date.dt.year >= lo) & (g.date.dt.year < hi)]
    x = pd.concat([y.iloc[::21] for _, y in x.groupby("sym")])
    r = x.fin21 / (x.sig * np.sqrt(21))
    print(f"    {lo}-{hi - 1}: n={len(x):4d}  mean {r.mean():+.3f}  t {r.mean() / (r.std(ddof=1) / np.sqrt(len(r))):+.2f}  P(up) {100 * (x.fin21 > 0).mean():.0f}%")
print("\n  per commodity, 'strong up' -> next 21 days (mean return %, t):")
for sym, name in SYMS.items():
    x = D[(D.sym == sym) & (D.bucket == "strong up") & D.fin21.notna()].iloc[::21]
    r = x.fin21 / (x.sig * np.sqrt(21))
    print(f"    {name:12s} n={len(x):3d}  {100 * x.fin21.mean():+6.2f}%  t {r.mean() / (r.std(ddof=1) / np.sqrt(len(r))):+.2f}")

# ---------- 3. Time of the peak and how much is given back: needed for the "how will it sell" answer
print("\n=== 3. Where is the peak and how much is given back? (all days, 21-day window) ===")
x = D[D.fin21.notna()]
giveback = (x.peak21 - x.fin21)
print(f"  median day of peak inside the window: {x.tpk21.median():.0f} of 21;  P(peak is on the last day) {100 * (x.tpk21 == 21).mean():.0f}%;  P(peak on day 1-3) {100 * (x.tpk21 <= 3).mean():.0f}%")
print(f"  median give-back from the peak to the 21-day close: {100 * giveback.median():.2f}% of price (in vol units {np.median(giveback / (x.sig * np.sqrt(21))):.2f})")
