"""Why does the same-day model still lean DOWN, and does market-wide context fix it?

Live (since 1 Oct): 86-98% of first calls predict down, uncorrelated with the day's real move; on 13 Oct (46/50 stocks
up) direction was right on 20%. Centring the target (lean_ablation.py) helped in the backtest (hour-0 down share 82% ->
61%) but live barely moved (92% -> 86%). Hypotheses tested here, walk-forward, same harness/hyperparameters as production:

  B  production now (target centred per hour)
  D  B + MARKET context: basketRetMean (mean first-N-hours return of the 50 stocks at the same day/hour) and
     relRet (the stock's return minus that mean). The reversal the model learned is about IDIOSYNCRATIC moves; on a
     market-wide move (breadth) it should trust it less. Computable live: all 50 snapshots are fetched together already.
  F  B minus the two reversal-driving inputs (returnSoFarPct, intradayObv): how much of the lean/accuracy is the reversal?

Also: backtest hour-0 'share predicting down' per day for the live era next to the live ledger's numbers, to separate
"the regime" from "live inputs differ from training inputs" (live first call is made ~10:13 IST from a PARTIAL first
candle; training rows use the complete candle).
Evaluation slices: all rows hours 0-5; days where the basket moved market-wide in the first hour (|mean| >= 0.4%).
"""
import os
import sys

import numpy as np
import pandas as pd
from scipy import stats

sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

LIVE_DOWN_SHARE = {"2026-09-23": 84, "2026-09-24": 84, "2026-09-25": 78, "2026-09-28": 40, "2026-09-29": 36, "2026-09-30": 68,
                   "2026-10-01": 92, "2026-10-05": 98, "2026-10-06": 98, "2026-10-07": 86, "2026-10-08": 94, "2026-10-09": 94,
                   "2026-10-12": 92, "2026-10-13": 86}

df = va.build_frame()
stocks = ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])
r = pd.to_numeric(df["returnSoFarPct"], errors="coerce")
mean_by = r[stocks].groupby([df.loc[stocks, "tradingDate"], df.loc[stocks, "hoursSinceOpen"]]).mean()
df["basketRetMean"] = [mean_by.get((d, h), np.nan) for d, h in zip(df["tradingDate"], df["hoursSinceOpen"])]
df["relRet"] = r - df["basketRetMean"]
kept, _ = va.m.select_intraday_features(df)
kept = [f for f in kept if f != "resultsDayOffset"]
variants = {"B_prod_now": kept, "D_market_ctx": kept + ["basketRetMean", "relRet"],
            "F_no_reversal": [f for f in kept if f not in ("returnSoFarPct", "intradayObv")]}
days = sorted(df["tradingDate"].unique()); test_days = days[-va.N_TEST_DAYS:]
block = len(test_days) // va.N_FOLDS
folds = [test_days[i * block:(i + 1) * block if i < va.N_FOLDS - 1 else len(test_days)] for i in range(va.N_FOLDS)]
res = {k: [] for k in variants}
for i, fd in enumerate(folds, 1):
    train = df[df["tradingDate"] < fd[0]].copy()
    test = df[df["tradingDate"].isin(fd) & stocks]
    med = train.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    t2 = train.copy(); t2["remainingDriftPct"] -= t2["hoursSinceOpen"].map(med)     # same centring as production
    print(f"fold {i}/{va.N_FOLDS}", flush=True)
    for name, feats in variants.items():
        lo, md, hi = va.fit_predict(t2, test, feats)
        out = test[["tradingDate", "instrument", "hoursSinceOpen", "remainingDriftPct", "basketRetMean"]].copy()
        out["lo"], out["md"], out["hi"] = lo, md, hi
        res[name].append(out)
res = {k: pd.concat(v, ignore_index=True) for k, v in res.items()}
Bm = res["B_prod_now"]
early = (Bm.hoursSinceOpen <= 5).to_numpy()
pin = lambda x: (va.pinball(x.remainingDriftPct.to_numpy(), x.lo.to_numpy(), .05) + va.pinball(x.remainingDriftPct.to_numpy(), x.md.to_numpy(), .5)
                 + va.pinball(x.remainingDriftPct.to_numpy(), x.hi.to_numpy(), .95)) / 3
trend = (Bm.basketRetMean.abs() >= 0.4).to_numpy()


def row(name, x, mask):
    x = x[mask]; y = x.remainingDriftPct.to_numpy(); nz = np.abs(y) > 1e-9
    mae = np.abs(y - x.md).mean()
    return (f"{name:14s} n={len(x):6d} MAE {mae:.4f} ({100 * (1 - mae / np.abs(y).mean()):+.2f}% vs no-chg) pinball {pin(x).mean():.5f} "
            f"down {100 * (x.md < 0).mean():3.0f}% dir {100 * (np.sign(x.md[nz]) == np.sign(y[nz])).mean():.1f}% IC {stats.spearmanr(x.md, y)[0]:+.3f}")


for lab, mask in (("ALL rows hours 0-5", early), ("MARKET-WIDE first-hour move days (|basket mean| >= 0.4%)", early & trend),
                  ("quiet days (|basket mean| < 0.4%)", early & ~trend)):
    print(f"\n=== {lab} ===")
    for name, x in res.items():
        print("  " + row(name, x, mask))
print("\n=== paired across days vs B (positive = variant better), hours 0-5 ===")
for name in ("D_market_ctx", "F_no_reversal"):
    a = res[name][early]; b = Bm[early]
    d = pd.Series(pin(b) - pin(a)).groupby(a.tradingDate.to_numpy()).mean(); n = len(d)
    t = d.mean() / (d.std(ddof=1) / np.sqrt(n))
    e = pd.Series(np.abs(b.remainingDriftPct - b.md).to_numpy() - np.abs(a.remainingDriftPct - a.md).to_numpy()).groupby(a.tradingDate.to_numpy()).mean()
    te = e.mean() / (e.std(ddof=1) / np.sqrt(n))
    print(f"  {name:14s} pinball gain {d.mean():+.6f} t={t:+.2f} p={2 * (1 - stats.t.cdf(abs(t), n - 1)):.3f} | point-error gain {e.mean():+.6f} t={te:+.2f} p={2 * (1 - stats.t.cdf(abs(te), n - 1)):.3f}")

print("\n=== does the day's share of DOWN calls follow the market? (hour-0, per day) ===")
for name, x in res.items():
    h0 = x[x.hoursSinceOpen == 0]
    g = h0.groupby("tradingDate").agg(down=("md", lambda s: (s < 0).mean()), brk=("basketRetMean", "first"), mv=("remainingDriftPct", "mean"))
    print(f"  {name:14s} mean down {100 * g.down.mean():3.0f}% (std {100 * g.down.std():2.0f} pts) | corr(share down, first-hour basket mean) {stats.spearmanr(g.down, g.brk)[0]:+.2f} "
          f"| corr(share down, rest-of-day basket move) {stats.spearmanr(g.down, g.mv)[0]:+.2f}")

print("\n=== live era: backtest hour-0 share predicting DOWN vs what the live ledger recorded ===")
print(f"  {'day':11s} {'live':>6s} " + " ".join(f"{n[:12]:>12s}" for n in res) + "   basket 1st-hr mean")
for d, live in LIVE_DOWN_SHARE.items():
    cells = []
    for name, x in res.items():
        h0 = x[(x.hoursSinceOpen == 0) & (x.tradingDate == d)]
        cells.append(f"{100 * (h0.md < 0).mean():11.0f}%" if len(h0) else f"{'n/a':>12s}")
    h = Bm[(Bm.hoursSinceOpen == 0) & (Bm.tradingDate == d)]
    print(f"  {d:11s} {live:5d}% " + " ".join(cells) + f"   {h.basketRetMean.mean():+.2f}%" if len(h) else f"  {d:11s} {live:5d}%  (not in test window)")
