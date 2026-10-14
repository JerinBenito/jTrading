"""Dose-response test: if live calls are made from a PARTIAL candle (volume so far understated), does the
production-style model's share of DOWN calls jump toward the live 84-98%? Trains the production feature set
(target centred per hour) on everything before the last 60 trading days, then scores the last 60 days with
volumeSoFarRatio (and the intradayObv that multiplies it) scaled by f. f=1.0 is the training-time input."""
import os, sys
import numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

df = va.build_frame()
kept, _ = va.m.select_intraday_features(df)
kept = [f for f in kept if f != "resultsDayOffset"]
days = sorted(df["tradingDate"].unique()); cut = days[-60]
train = df[df["tradingDate"] < cut].copy(); test = df[(df["tradingDate"] >= cut) & ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])].copy()
med = train.groupby("hoursSinceOpen")["remainingDriftPct"].median(); train["remainingDriftPct"] -= train["hoursSinceOpen"].map(med)
models = {}
for name, a in (("low", .05), ("med", .5), ("high", .95)):
    from lightgbm import LGBMRegressor
    m = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1, objective="quantile", alpha=a)
    m.fit(train[kept], train["remainingDriftPct"]); models[name] = m
print(f"trained on {len(train)} rows; testing {test.tradingDate.nunique()} days {cut}..{days[-1]}")
print(f"{'volume factor':>14s} {'hour0 down%':>12s} {'hours1-5 down%':>15s} {'mean drift h0':>14s}")
for f in (1.0, 0.9, 0.75, 0.6, 0.5):
    t = test.copy()
    t["volumeSoFarRatio"] = t["volumeSoFarRatio"] * f
    t["intradayObv"] = t["intradayObv"] * f
    pred = models["med"].predict(t[kept])
    h0 = t.hoursSinceOpen.to_numpy() == 0; h15 = (t.hoursSinceOpen.to_numpy() >= 1) & (t.hoursSinceOpen.to_numpy() <= 5)
    print(f"{f:14.2f} {100 * (pred[h0] < 0).mean():11.0f}% {100 * (pred[h15] < 0).mean():14.0f}% {pred[h0].mean():+13.3f}%")
# which single input moves predictions the most when set to a partial-candle-like value?
print("\nsensitivity at hour 0: share DOWN when ONE input is changed (others as trained)")
base = test[test.hoursSinceOpen == 0].copy()
for label, change in (("none", lambda t: t), ("volumeSoFarRatio x0.75", lambda t: t.assign(volumeSoFarRatio=t.volumeSoFarRatio * .75)),
                      ("intradayObv x0.75", lambda t: t.assign(intradayObv=t.intradayObv * .75)),
                      ("returnSoFarPct x0.75", lambda t: t.assign(returnSoFarPct=t.returnSoFarPct * .75)),
                      ("volatilitySoFarPct x0.75", lambda t: t.assign(volatilitySoFarPct=t.volatilitySoFarPct * .75))):
    t = change(base); pred = models["med"].predict(t[kept])
    print(f"  {label:26s} down {100 * (pred < 0).mean():3.0f}%  mean {pred.mean():+.3f}%")
