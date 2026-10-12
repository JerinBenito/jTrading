"""Walk-forward test of the 'down-lean' fix for the same-day quantile model.

Problem (live, since 2026-10-01): 86-98% of first calls predict DOWN, uncorrelated (-0.08) with the day's real move.
Cause hypothesis: the training target's average remaining drift is slightly negative (the basket drifted down over
the sample), so the median model sits below zero for almost everyone and only a few features ever override it.
Fix tested: subtract each hour's TRAIN-SET median remaining drift from the target, fit on that, and DO NOT add it back
(so the forecast is centred on 'no drift' and moves only when the features say so). Same harness/hyperparameters as
production (volume_features_ablation.py); the medians come from the training window only.
A = production target, B = demeaned target.
"""
import os, sys
import numpy as np, pandas as pd
from scipy import stats
sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

df = va.build_frame()
kept, _ = va.m.select_intraday_features(df)
days = sorted(df["tradingDate"].unique()); test_days = days[-va.N_TEST_DAYS:]
block = len(test_days) // va.N_FOLDS
folds = [test_days[i * block:(i + 1) * block if i < va.N_FOLDS - 1 else len(test_days)] for i in range(va.N_FOLDS)]
res = {"A_prod": [], "B_demeaned": []}
for i, fd in enumerate(folds, 1):
    train = df[df["tradingDate"] < fd[0]].copy()
    test = df[df["tradingDate"].isin(fd) & ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])]
    med = train.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    t2 = train.copy(); t2["remainingDriftPct"] = t2["remainingDriftPct"] - t2["hoursSinceOpen"].map(med)
    print(f"fold {i}/{va.N_FOLDS}: train median remaining drift by hour {dict(med.round(3))}", flush=True)
    for name, tr in (("A_prod", train), ("B_demeaned", t2)):
        lo, md, hi = va.fit_predict(tr, test, kept)
        out = test[["tradingDate", "instrument", "hoursSinceOpen", "remainingDriftPct"]].copy()
        out["lo"], out["md"], out["hi"] = lo, md, hi
        res[name].append(out)
res = {k: pd.concat(v, ignore_index=True) for k, v in res.items()}
A, B = res["A_prod"], res["B_demeaned"]
early = (A.hoursSinceOpen <= 5).to_numpy()
pin = lambda r: (va.pinball(r.remainingDriftPct.to_numpy(), r.lo.to_numpy(), .05) + va.pinball(r.remainingDriftPct.to_numpy(), r.md.to_numpy(), .5)
                 + va.pinball(r.remainingDriftPct.to_numpy(), r.hi.to_numpy(), .95)) / 3
print("\n=== hours 0-5 rows, out of sample ===")
print(f"{'variant':12s} {'MAE%':>7s} {'vs no-chg':>10s} {'pinball':>9s} {'cover%':>7s} {'width':>6s} {'pred-down%':>11s} {'dir%':>6s} {'IC':>7s}")
for name, r in (("A_prod", A[early]), ("B_demeaned", B[early])):
    y = r.remainingDriftPct.to_numpy(); mae = np.abs(y - r.md).mean(); nz = np.abs(y) > 1e-9
    print(f"{name:12s} {mae:7.4f} {100 * (1 - mae / np.abs(y).mean()):9.2f}% {pin(r).mean():9.5f} {100 * ((y >= r.lo) & (y <= r.hi)).mean():7.1f} "
          f"{(r.hi - r.lo).mean():6.3f} {100 * (r.md < 0).mean():10.1f}% {100 * (np.sign(r.md[nz]) == np.sign(y[nz])).mean():6.1f} {stats.spearmanr(r.md, y)[0]:+7.4f}")
a, b = A[early], B[early]
for lab, fn in (("point error (abs)", lambda r: np.abs(r.remainingDriftPct.to_numpy() - r.md.to_numpy())), ("quantile pinball", pin)):
    diff = pd.Series(fn(a) - fn(b)).groupby(a.tradingDate.to_numpy()).mean()   # positive = B better
    n = len(diff); t = diff.mean() / (diff.std(ddof=1) / np.sqrt(n))
    print(f"paired over {n} days, {lab:18s}: B better on {100 * (diff > 0).mean():.1f}% of days, mean gain {diff.mean():+.6f}, t={t:+.2f}, p={2 * (1 - stats.t.cdf(abs(t), n - 1)):.3f}")
# per-day: does the share of 'down' calls track the market now?
for name, r in (("A_prod", A[early & (A.hoursSinceOpen == 0).to_numpy()]), ("B_demeaned", B[early & (B.hoursSinceOpen == 0).to_numpy()])):
    g = r.groupby("tradingDate").agg(down=("md", lambda s: (s < 0).mean()), mv=("remainingDriftPct", "mean"))
    print(f"{name}: hour-0 calls - mean share predicting down {100 * g.down.mean():.0f}% (std across days {100 * g.down.std():.0f} pts), corr with the day's average move {stats.spearmanr(g.down, g.mv)[0]:+.3f}")
