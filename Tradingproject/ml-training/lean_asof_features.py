"""As-of replay (train strictly before D, production recipe) with vs without the YOUNG day-constant features
(pcrLatest, recentPatternWinRate, recentPatternDirection, deterministicDeviationPct): do they cause the lean, and what
do they do to accuracy? Scores hours 0-5 of each of the last N days with the median model.
P = production gate (admit a young feature after 30 distinct days); X = those features excluded."""
import os, sys
import numpy as np, pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats
sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

N_DAYS = 18
DAYCONST = ["pcrLatest", "recentPatternWinRate", "recentPatternDirection", "deterministicDeviationPct", "hmmDeviationPct", "garchRangeWidthPct",
            "globalSp500ChangePct", "globalCrudeOilChangePct", "globalUsdInrChangePct", "globalIndiaVixLevel"]
df = va.build_frame(); m = va.m
stock = ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])
days = [d for d in sorted(df["tradingDate"].unique())][-N_DAYS:]
rows = []
for d in days:
    train = df[df["tradingDate"] < d].copy()
    kept, _ = m.select_intraday_features(train)
    med = train.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    y = train["remainingDriftPct"] - train["hoursSinceOpen"].map(med)
    test = df[(df["tradingDate"] == d) & stock & (df["hoursSinceOpen"] <= 5)]
    for name, feats in (("P_prod", kept), ("X_no_young", [f for f in kept if f not in DAYCONST])):
        mod = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1, objective="quantile", alpha=0.5)
        mod.fit(train[feats], y)
        p = mod.predict(test[feats])
        for hh, pp, yy in zip(test.hoursSinceOpen, p, test.remainingDriftPct):
            rows.append((d, name, hh, pp, yy))
    print(d, flush=True)
r = pd.DataFrame(rows, columns=["day", "variant", "hour", "pred", "y"])
print("\n=== per day: share of hour-0 calls DOWN and the day's mean remaining move ===")
h0 = r[r.hour == 0].groupby(["day", "variant"]).agg(down=("pred", lambda s: (s < 0).mean()), mv=("y", "mean")).unstack()
print((h0["down"] * 100).round(0).assign(market_move=h0["mv"]["P_prod"].round(2)).to_string())
print("\n=== all rows hours 0-5 over the last", N_DAYS, "days ===")
for v, g in r.groupby("variant"):
    nz = g.y.abs() > 1e-9
    mae = (g.y - g.pred).abs().mean(); base = g.y.abs().mean()
    print(f"  {v:11s} n={len(g)} MAE {mae:.4f} vs no-change {base:.4f} ({100 * (1 - mae / base):+.2f}%) | down {100 * (g.pred < 0).mean():.0f}% | dir {100 * (np.sign(g.pred[nz]) == np.sign(g.y[nz])).mean():.1f}% | IC {stats.spearmanr(g.pred, g.y)[0]:+.3f}")
a = r[r.variant == "P_prod"].reset_index(drop=True); b = r[r.variant == "X_no_young"].reset_index(drop=True)
d_ = pd.Series((a.y - a.pred).abs() - (b.y - b.pred).abs()).groupby(a.day).mean()   # positive = X better
t = d_.mean() / (d_.std(ddof=1) / np.sqrt(len(d_)))
print(f"  paired by day (positive = X better): mean {d_.mean():+.5f}, X better on {100 * (d_ > 0).mean():.0f}% of {len(d_)} days, t={t:+.2f}, p={2 * (1 - stats.t.cdf(abs(t), len(d_) - 1)):.3f}")
