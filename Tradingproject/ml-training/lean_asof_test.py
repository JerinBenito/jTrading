"""Replay production AS OF each recent day: train exactly like run_daily_ai_prediction.train_intraday_model on days
strictly before D (young-feature gate evaluated on that history, target centred per hour), then predict D's hour-0
rows built from the COMPLETE export. Compare the share of DOWN calls with the live ledger's share for the same day.
Matches live -> the lean is in the model/training-set composition. Far below live -> the live inputs differ from export."""
import os, sys, time
import numpy as np, pandas as pd
from lightgbm import LGBMRegressor
sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

LIVE = {"2026-10-01": 92, "2026-10-05": 98, "2026-10-06": 98, "2026-10-07": 86, "2026-10-08": 94, "2026-10-09": 94, "2026-10-12": 92, "2026-10-13": 86}
df = va.build_frame(); m = va.m
stock = ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])
print(f"{'day':11s} {'live':>5s} {'as-of replay':>13s} {'mean drift':>11s}  features admitted (young ones)")
for d, live in LIVE.items():
    train = df[df["tradingDate"] < d].copy()
    kept, gated = m.select_intraday_features(train)
    med = train.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    y = train["remainingDriftPct"] - train["hoursSinceOpen"].map(med)
    model = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1, objective="quantile", alpha=0.5)
    model.fit(train[kept], y)
    t = df[(df["tradingDate"] == d) & (df["hoursSinceOpen"] == 0) & stock]
    pred = model.predict(t[kept])
    young = sorted(set(kept) & set(m.YOUNG_SUPPLEMENTARY_FEATURES + m.AI_SELF_FEATURES))
    print(f"{d:11s} {live:4d}% {100 * (pred < 0).mean():12.0f}% {pred.mean():+10.3f}%  {young}", flush=True)
