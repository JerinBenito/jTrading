import json
import os
import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor
from sklearn.metrics import mean_absolute_error

DATA_PATH = os.path.join(os.path.dirname(__file__), "data", "intraday_features.json")

with open(DATA_PATH) as f:
    rows = json.load(f)
df = pd.DataFrame(rows)
print(f"Total rows: {len(df)}")
print(f"Instruments: {df['instrument'].nunique()}")
print(f"Date range: {df['tradingDate'].min()} to {df['tradingDate'].max()}")

FEATURES = [
    "hoursSinceOpen", "returnSoFarPct", "volatilitySoFarPct", "rsi14", "emaSpreadPct",
    "bodyPct", "upperWickPct", "lowerWickPct", "last3UpCount", "volumeSoFarRatio",
]
TARGET = "remainingDriftPct"

# Chronological split (NOT random shuffle) - train on the earlier ~80% of dates, test on the
# most recent ~20%, matching this project's no-lookahead walk-forward discipline throughout.
dates = sorted(df["tradingDate"].unique())
cutoff = dates[int(len(dates) * 0.8)]
train_df = df[df["tradingDate"] < cutoff].copy()
test_df = df[df["tradingDate"] >= cutoff].copy()
print(f"\nSplit cutoff date: {cutoff}")
print(f"Train rows: {len(train_df)} ({train_df['tradingDate'].min()} to {train_df['tradingDate'].max()})")
print(f"Test rows:  {len(test_df)} ({test_df['tradingDate'].min()} to {test_df['tradingDate'].max()})")

X_train, y_train = train_df[FEATURES], train_df[TARGET]
X_test, y_test = test_df[FEATURES], test_df[TARGET]

model = LGBMRegressor(
    n_estimators=300,
    max_depth=5,
    learning_rate=0.03,
    subsample=0.8,
    colsample_bytree=0.8,
    random_state=42,
    verbosity=-1,
)
model.fit(X_train, y_train)

predicted_drift = model.predict(X_test)
predicted_close_ml = test_df["currentPrice"].values * (1 + predicted_drift / 100)
predicted_close_baseline = test_df["currentPrice"].values  # zero drift assumed - the existing baseline
actual_close = test_df["actualFinalClose"].values

ml_error_pct = np.abs(actual_close - predicted_close_ml) / actual_close * 100
baseline_error_pct = np.abs(actual_close - predicted_close_baseline) / actual_close * 100

print(f"\n=== Overall (all hours pooled, {len(test_df)} test rows) ===")
print(f"Baseline (assume current price holds) mean abs error: {baseline_error_pct.mean():.4f}%")
print(f"LightGBM model mean abs error:                        {ml_error_pct.mean():.4f}%")
print(f"Improvement: {baseline_error_pct.mean() - ml_error_pct.mean():.4f} percentage points "
      f"({'ML WINS' if ml_error_pct.mean() < baseline_error_pct.mean() else 'baseline still wins'})")

print("\n=== By hour-since-open ===")
test_df = test_df.copy()
test_df["ml_error_pct"] = ml_error_pct
test_df["baseline_error_pct"] = baseline_error_pct
by_hour = test_df.groupby("hoursSinceOpen").agg(
    n=("ml_error_pct", "size"),
    baseline_mae=("baseline_error_pct", "mean"),
    ml_mae=("ml_error_pct", "mean"),
).reset_index()
by_hour["ml_wins"] = by_hour["ml_mae"] < by_hour["baseline_mae"]
print(by_hour.to_string(index=False))

print("\n=== Feature importance ===")
importances = pd.Series(model.feature_importances_, index=FEATURES).sort_values(ascending=False)
print(importances.to_string())

# Direction accuracy - does the model at least get the SIGN of the remaining move right more
# often than chance, even if the magnitude is off? A weaker but still meaningful signal.
actual_direction = np.sign(test_df[TARGET].values)
predicted_direction = np.sign(predicted_drift)
nonzero_actual = actual_direction != 0
direction_accuracy = (actual_direction[nonzero_actual] == predicted_direction[nonzero_actual]).mean()
print(f"\n=== Direction accuracy (did the model get up-vs-down right?) ===")
print(f"{direction_accuracy * 100:.2f}% (50% = coin flip)")
