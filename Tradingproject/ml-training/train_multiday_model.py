import json
import os
import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor

DATA_PATH = os.path.join(os.path.dirname(__file__), "data", "ml_features_all.json")

with open(DATA_PATH) as f:
    rows = json.load(f)
df = pd.DataFrame(rows)
df["tradingDate"] = pd.to_datetime(df["tradingDate"])
print(f"Total rows: {len(df)}, instruments: {df['instrument'].nunique()}")
print(f"Date range: {df['tradingDate'].min().date()} to {df['tradingDate'].max().date()}")

FEATURES = [
    "dailyReturnPct", "gapFromPrevClosePct", "intradayRangePct",
    "emaSpreadPct", "rsi14", "atr14",
    "return5dPct", "return10dPct", "return20dPct", "return40dPct",
    "bodyPct", "upperWickPct", "lowerWickPct",
]

dates = sorted(df["tradingDate"].unique())
cutoff = dates[int(len(dates) * 0.8)]
print(f"Split cutoff: {pd.Timestamp(cutoff).date()}")

for horizon in [5, 10, 20, 40]:
    target = f"forwardReturn{horizon}dPct"
    sub = df.dropna(subset=FEATURES + [target]).copy()

    train = sub[sub["tradingDate"] < cutoff]
    test = sub[sub["tradingDate"] >= cutoff]
    if len(train) < 200 or len(test) < 50:
        print(f"\n=== Horizon {horizon}d: insufficient data (train={len(train)}, test={len(test)}) ===")
        continue

    X_train, y_train = train[FEATURES], train[target]
    X_test, y_test = test[FEATURES], test[target]

    model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03,
                           subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
    model.fit(X_train, y_train)
    pred = model.predict(X_test)

    # Baseline: predict the historical mean forward return (computed from TRAIN only, no
    # lookahead) - the honest "no model" comparison point for a return target (predicting 0
    # would ignore known long-run drift; the train-set mean is the fair naive baseline).
    baseline_pred = np.full_like(y_test.values, y_train.mean())

    ml_mae = np.abs(y_test.values - pred).mean()
    baseline_mae = np.abs(y_test.values - baseline_pred).mean()

    actual_direction = np.sign(y_test.values)
    pred_direction = np.sign(pred - y_train.mean())  # direction relative to the naive baseline
    nonzero = actual_direction != 0
    direction_acc = (actual_direction[nonzero] == pred_direction[nonzero]).mean()

    corr = np.corrcoef(pred, y_test.values)[0, 1]

    print(f"\n=== Horizon {horizon}d (train={len(train)}, test={len(test)}) ===")
    print(f"Baseline (train-set mean) MAE: {baseline_mae:.4f} pct-points")
    print(f"LightGBM MAE:                 {ml_mae:.4f} pct-points  "
          f"({'ML WINS' if ml_mae < baseline_mae else 'baseline wins'})")
    print(f"Correlation(predicted, actual): {corr:.4f}")
    print(f"Direction accuracy: {direction_acc*100:.2f}% (50% = coin flip)")
