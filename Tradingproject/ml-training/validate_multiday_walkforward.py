"""Rigorous validation of the multi-day (20/40-day) forward-return signal found by
train_multiday_model.py, which used only a single train/test split - not enough to trust given
this project's own standard (every validated finding elsewhere used multiple time windows).

Uses expanding-window walk-forward: the historical date range is cut into several chronological
blocks; for each fold, the model trains on everything before a block and is tested on that block
only (never on data from its own future). Reports per-fold results, not just an average, since a
signal that only shows up in one fold out of several is not a real signal.
"""
import json
import os
import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor

DATA_PATH = os.path.join(os.path.dirname(__file__), "data", "ml_features_all.json")
NUM_FOLDS = 5  # first block is used only as initial training data, so this gives 4 test folds
HORIZONS = [5, 10, 20, 40]

# volumeRatio20d is null for NIFTY/BANKNIFTY (no real index volume) - kept as a feature anyway
# (not required in dropna) since LightGBM handles missing values natively.
REQUIRED_FEATURES = [
    "dailyReturnPct", "gapFromPrevClosePct", "intradayRangePct",
    "emaSpreadPct", "rsi14", "atr14",
    "return5dPct", "return10dPct", "return20dPct", "return40dPct",
    "bodyPct", "upperWickPct", "lowerWickPct",
]
FEATURES = REQUIRED_FEATURES + ["volumeRatio20d"]


def run():
    with open(DATA_PATH) as f:
        rows = json.load(f)
    df = pd.DataFrame(rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    print(f"Total rows: {len(df)}, instruments: {df['instrument'].nunique()}")
    print(f"Date range: {df['tradingDate'].min().date()} to {df['tradingDate'].max().date()}")

    dates = sorted(df["tradingDate"].unique())
    block_edges = np.linspace(0, len(dates), NUM_FOLDS + 1, dtype=int)
    block_starts = [dates[block_edges[i]] for i in range(NUM_FOLDS)]
    block_ends = [dates[min(block_edges[i + 1], len(dates) - 1)] for i in range(NUM_FOLDS)]
    print(f"\n{NUM_FOLDS} chronological blocks:")
    for i, (s, e) in enumerate(zip(block_starts, block_ends)):
        print(f"  Block {i}: {pd.Timestamp(s).date()} to {pd.Timestamp(e).date()}")

    for horizon in HORIZONS:
        target = f"forwardReturn{horizon}dPct"
        sub = df.dropna(subset=REQUIRED_FEATURES + [target]).copy()

        print(f"\n{'=' * 70}\nHORIZON: {horizon} days\n{'=' * 70}")
        fold_results = []
        for fold in range(1, NUM_FOLDS):
            train = sub[sub["tradingDate"] < block_starts[fold]]
            test = sub[(sub["tradingDate"] >= block_starts[fold]) & (sub["tradingDate"] <= block_ends[fold])]
            if len(train) < 500 or len(test) < 100:
                print(f"Fold {fold}: skipped (train={len(train)}, test={len(test)}, too small)")
                continue

            model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03,
                                   subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
            model.fit(train[FEATURES], train[target])
            pred = model.predict(test[FEATURES])

            baseline_pred = np.full(len(test), train[target].mean())
            ml_mae = np.abs(test[target].values - pred).mean()
            baseline_mae = np.abs(test[target].values - baseline_pred).mean()
            corr = np.corrcoef(pred, test[target].values)[0, 1]

            actual_dir = np.sign(test[target].values)
            pred_dir = np.sign(pred - train[target].mean())
            nz = actual_dir != 0
            direction_acc = (actual_dir[nz] == pred_dir[nz]).mean()

            ml_wins = ml_mae < baseline_mae
            fold_results.append(ml_wins)
            print(f"Fold {fold} [{pd.Timestamp(block_starts[fold]).date()} to "
                  f"{pd.Timestamp(block_ends[fold]).date()}] (train={len(train)}, test={len(test)}): "
                  f"baseline_mae={baseline_mae:.4f} ml_mae={ml_mae:.4f} "
                  f"corr={corr:+.4f} dir_acc={direction_acc*100:.2f}% "
                  f"-> {'ML WINS' if ml_wins else 'baseline wins'}")

        if fold_results:
            wins = sum(fold_results)
            print(f"\nSummary for {horizon}d: ML beat baseline in {wins}/{len(fold_results)} folds")


if __name__ == "__main__":
    run()
