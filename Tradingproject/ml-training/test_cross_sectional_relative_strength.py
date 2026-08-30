"""Tests cross-sectional relative strength: does a stock's performance RELATIVE to the other
50 basket stocks (not its own absolute price history) predict its FUTURE relative performance?

This is a different, more-replicated hypothesis in real quant research than the time-series
momentum already tested and rejected in Phase C (MomentumBacktestService: correlation ~0 using
absolute past return alone). The idea: market-wide moves (index going up/down) are noise for this
question; what matters is whether THIS stock is outperforming or underperforming its peers, and
whether that relative standing persists.

For each (instrument, date), computes relative past/future returns as (this stock's return) minus
(the cross-sectional MEDIAN return across all instruments trading that day, at the same horizon)
- this differences out market-wide moves, isolating genuinely stock-specific relative performance.
Then: (1) simple correlation check per horizon (like Phase C's original test, but relative instead
of absolute), (2) full LightGBM walk-forward validation, same multi-fold discipline as
validate_multiday_walkforward.py, to catch a lucky single split before trusting anything.
"""
import json
import os
import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor

DATA_PATH = os.path.join(os.path.dirname(__file__), "data", "ml_features_all.json")
NUM_FOLDS = 5
HORIZONS = [5, 10, 20, 40]

LOOKBACK_COLS = {h: f"return{h}dPct" for h in HORIZONS}
FORWARD_COLS = {h: f"forwardReturn{h}dPct" for h in HORIZONS}


def add_relative_columns(df):
    for h in HORIZONS:
        lookback_col, forward_col = LOOKBACK_COLS[h], FORWARD_COLS[h]
        lookback_median = df.groupby("tradingDate")[lookback_col].transform("median")
        forward_median = df.groupby("tradingDate")[forward_col].transform("median")
        df[f"rel_{lookback_col}"] = df[lookback_col] - lookback_median
        df[f"rel_{forward_col}"] = df[forward_col] - forward_median
    return df


def run():
    with open(DATA_PATH) as f:
        rows = json.load(f)
    df = pd.DataFrame(rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    df = add_relative_columns(df)
    print(f"Total rows: {len(df)}, instruments: {df['instrument'].nunique()}")
    print(f"Date range: {df['tradingDate'].min().date()} to {df['tradingDate'].max().date()}")

    print("\n" + "=" * 70)
    print("STEP 1: Simple correlation check (relative past return vs relative future return)")
    print("=" * 70)
    for h in HORIZONS:
        rel_past = f"rel_{LOOKBACK_COLS[h]}"
        rel_future = f"rel_{FORWARD_COLS[h]}"
        sub = df.dropna(subset=[rel_past, rel_future])
        corr = sub[rel_past].corr(sub[rel_future])
        print(f"{h:>2}d: correlation(relative past return, relative future return) = {corr:+.4f}  (n={len(sub)})")

    print("\n" + "=" * 70)
    print("STEP 2: Walk-forward LightGBM (predicting relative future return from relative")
    print("features + the existing technical features), 4 independent folds per horizon")
    print("=" * 70)

    REQUIRED_FEATURES = [
        "dailyReturnPct", "gapFromPrevClosePct", "intradayRangePct",
        "emaSpreadPct", "rsi14", "atr14", "bodyPct", "upperWickPct", "lowerWickPct",
    ] + [f"rel_{LOOKBACK_COLS[h]}" for h in HORIZONS]

    dates = sorted(df["tradingDate"].unique())
    block_edges = np.linspace(0, len(dates), NUM_FOLDS + 1, dtype=int)
    block_starts = [dates[block_edges[i]] for i in range(NUM_FOLDS)]
    block_ends = [dates[min(block_edges[i + 1], len(dates) - 1)] for i in range(NUM_FOLDS)]

    for h in HORIZONS:
        target = f"rel_{FORWARD_COLS[h]}"
        sub = df.dropna(subset=REQUIRED_FEATURES + [target]).copy()

        print(f"\n--- Horizon {h}d (relative) ---")
        fold_results = []
        for fold in range(1, NUM_FOLDS):
            train = sub[sub["tradingDate"] < block_starts[fold]]
            test = sub[(sub["tradingDate"] >= block_starts[fold]) & (sub["tradingDate"] <= block_ends[fold])]
            if len(train) < 500 or len(test) < 100:
                print(f"Fold {fold}: skipped (train={len(train)}, test={len(test)})")
                continue

            model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03,
                                   subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
            model.fit(train[REQUIRED_FEATURES], train[target])
            pred = model.predict(test[REQUIRED_FEATURES])

            baseline_pred = np.full(len(test), train[target].mean())  # ~0 by construction, but computed honestly
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
            print(f"Summary for {h}d relative: ML beat baseline in {sum(fold_results)}/{len(fold_results)} folds")


if __name__ == "__main__":
    run()
