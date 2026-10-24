"""Does the same-day AI beat 'no change' when judged out-of-sample on a MUCH longer history (hourly candles back to 2022)?

Walk-forward, expanding window, production recipe (target centred per hour, production features minus the young
day-constant ones), last N_TEST trading days in N_FOLDS folds; basket stocks only, hours 0-5. Reports overall and by
calendar year: MAE vs no-change, direction, rank IC, 90% range coverage, pinball. Day is the unit for significance.
TMPV's demerger window (Sep-Dec 2025: unadjusted ~-40% price steps) is excluded from rows.
"""
import os, sys
import numpy as np, pandas as pd
from scipy import stats

sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

va.N_TEST_DAYS = int(os.environ.get("N_TEST", 560))
va.N_FOLDS = int(os.environ.get("N_FOLDS", 8))
df = va.build_frame()
df = df[~((df["instrument"] == "TMPV") & (df["tradingDate"] >= "2025-09-20") & (df["tradingDate"] <= "2025-12-31"))]
kept, _ = va.m.select_intraday_features(df)
YOUNG = set(va.m.YOUNG_SUPPLEMENTARY_FEATURES + va.m.AI_SELF_FEATURES + ["resultsDayOffset"])
feats = [f for f in kept if f not in YOUNG]
print(f"{len(df)} rows, {df.tradingDate.nunique()} days {df.tradingDate.min()}..{df.tradingDate.max()}; features ({len(feats)}): {feats}", flush=True)
days = sorted(df["tradingDate"].unique()); test_days = days[-va.N_TEST_DAYS:]
block = len(test_days) // va.N_FOLDS
folds = [test_days[i * block:(i + 1) * block if i < va.N_FOLDS - 1 else len(test_days)] for i in range(va.N_FOLDS)]
stock = ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])
rows = []
for i, fd in enumerate(folds, 1):
    train = df[df["tradingDate"] < fd[0]].copy()
    test = df[df["tradingDate"].isin(fd) & stock]
    med = train.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    train["remainingDriftPct"] -= train["hoursSinceOpen"].map(med)
    lo, md, hi = va.fit_predict(train, test, feats)
    out = test[["tradingDate", "instrument", "hoursSinceOpen", "remainingDriftPct"]].copy()
    out["lo"], out["md"], out["hi"] = lo, md, hi
    rows.append(out)
    print(f"fold {i}/{va.N_FOLDS} train rows {len(train)} test {fd[0]}..{fd[-1]}", flush=True)
r = pd.concat(rows, ignore_index=True)
r = r[r.hoursSinceOpen <= 5]
r["year"] = r["tradingDate"].str[:4]
pin = lambda y, p, a: np.maximum(a * (y - p), (a - 1) * (y - p))


def summary(x, label):
    y = x.remainingDriftPct.to_numpy(); nz = np.abs(y) > 1e-9
    mae, base = np.abs(y - x.md).mean(), np.abs(y).mean()
    cov = ((y >= x.lo) & (y <= x.hi)).mean() * 100
    per_day = pd.Series(np.abs(y) - np.abs(y - x.md)).groupby(x.tradingDate.to_numpy()).mean()   # positive = AI better
    t = per_day.mean() / (per_day.std(ddof=1) / np.sqrt(len(per_day))) if len(per_day) > 2 else float("nan")
    print(f"{label:10s} n={len(x):7d} days={x.tradingDate.nunique():4d} | AI MAE {mae:.4f} vs no-change {base:.4f} ({100 * (1 - mae / base):+.2f}%, paired t={t:+.2f}) | "
          f"direction {100 * (np.sign(x.md[nz]) == np.sign(y[nz])).mean():.1f}% | IC {stats.spearmanr(x.md, y)[0]:+.4f} | range hit {cov:.1f}%")


print("\n=== same-day AI vs 'no change', out-of-sample, hours 0-5 ===")
summary(r, "ALL")
for yr, g in r.groupby("year"):
    summary(g, yr)
print("\nby hour since open:")
for h, g in r.groupby("hoursSinceOpen"):
    summary(g, f"hour {int(h)}")
