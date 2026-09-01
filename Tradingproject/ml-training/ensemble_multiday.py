"""Ensemble/stacking for the multi-day forward-return prediction, per Jerin's spec: feed the AI
the RAW OUTPUTS of multiple different modeling approaches (not a hint about which is best), and
let its own training process decide how to weight and combine them.

Base learners (the "opinions" being combined), per horizon:
  1. baseline   - historical mean forward return (the same baseline used everywhere else)
  2. absolute   - LightGBM on each stock's own absolute technical features (train_multiday_model.py)
  3. relative   - LightGBM on cross-sectional relative-strength features (test_cross_sectional_relative_strength.py)

Meta-model: another LightGBM, trained on the base learners' predictions (NOT the raw price
features) to predict the actual forward return. This is where the "AI decides" happens - nothing
here tells it which base learner to trust; it learns that from data.

Critical correctness point: the meta-model must never see a base learner's IN-SAMPLE prediction
(i.e. a prediction on data that learner was trained on) - that would let it learn to trust
whichever base learner overfit the training data hardest, not which one is genuinely useful. Both
layers are generated out-of-fold, walk-forward, same discipline as validate_multiday_walkforward.py.
"""
import json
import urllib.parse
import urllib.request

import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor

API_BASE = "https://jerintradingsignal.duckdns.org"
NUM_BLOCKS = 5
HORIZONS = [5, 10, 20, 40]

ABSOLUTE_FEATURES = [
    "dailyReturnPct", "gapFromPrevClosePct", "intradayRangePct",
    "emaSpreadPct", "rsi14", "atr14",
    "return5dPct", "return10dPct", "return20dPct", "return40dPct",
    "bodyPct", "upperWickPct", "lowerWickPct",
]
LOOKBACK_COLS = {h: f"return{h}dPct" for h in HORIZONS}
FORWARD_COLS = {h: f"forwardReturn{h}dPct" for h in HORIZONS}
RELATIVE_FEATURES = ["dailyReturnPct", "rsi14", "emaSpreadPct"] + [f"rel_{LOOKBACK_COLS[h]}" for h in HORIZONS]


def get_json(path):
    with urllib.request.urlopen(f"{API_BASE}{path}", timeout=60) as resp:
        return json.loads(resp.read())


def get_symbols():
    return [row["symbol"] for row in get_json("/api/monitor/basket")]


def download_data(symbols):
    print(f"Downloading data for {len(symbols)} instruments...")
    all_rows = []
    for sym in symbols:
        all_rows.extend(get_json(f"/api/ml/features/{urllib.parse.quote(sym)}"))
    df = pd.DataFrame(all_rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    for h in HORIZONS:
        lookback_col = LOOKBACK_COLS[h]
        df[f"rel_{lookback_col}"] = df[lookback_col] - df.groupby("tradingDate")[lookback_col].transform("median")
    return df


def make_blocks(df):
    dates = sorted(df["tradingDate"].unique())
    edges = np.linspace(0, len(dates), NUM_BLOCKS + 1, dtype=int)
    return [dates[edges[i]] for i in range(NUM_BLOCKS)], [dates[min(edges[i + 1], len(dates) - 1)] for i in range(NUM_BLOCKS)]


def train_predict(train_df, predict_df, features, target):
    model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03,
                           subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
    model.fit(train_df[features], train_df[target])
    return model.predict(predict_df[features])


def run_horizon(df, h, block_starts, block_ends):
    target = FORWARD_COLS[h]
    required = ABSOLUTE_FEATURES + RELATIVE_FEATURES + [target]
    sub = df.dropna(subset=list(set(required))).copy()

    # ---- Layer 1: out-of-fold base-learner predictions, one fold at a time ----
    oof_rows = []
    for fold in range(1, NUM_BLOCKS):
        train = sub[sub["tradingDate"] < block_starts[fold]]
        test = sub[(sub["tradingDate"] >= block_starts[fold]) & (sub["tradingDate"] <= block_ends[fold])]
        if len(train) < 500 or len(test) < 50:
            continue

        baseline_pred = np.full(len(test), train[target].mean())
        absolute_pred = train_predict(train, test, ABSOLUTE_FEATURES, target)
        relative_pred = train_predict(train, test, RELATIVE_FEATURES, target)

        fold_df = test[["instrument", "tradingDate", target]].copy()
        fold_df["fold"] = fold
        fold_df["baseline_pred"] = baseline_pred
        fold_df["absolute_pred"] = absolute_pred
        fold_df["relative_pred"] = relative_pred
        oof_rows.append(fold_df)

    if len(oof_rows) < 2:
        print(f"  {h}d: not enough folds with data, skipping")
        return

    oof = pd.concat(oof_rows, ignore_index=True)
    meta_features = ["baseline_pred", "absolute_pred", "relative_pred"]

    # ---- Layer 2: meta-model, itself walk-forward validated across the OOF folds ----
    print(f"\n--- Horizon {h}d ---")
    fold_ids = sorted(oof["fold"].unique())
    meta_wins, base_learner_wins = 0, {"baseline_pred": 0, "absolute_pred": 0, "relative_pred": 0}
    evaluated_folds = 0

    for test_fold in fold_ids[1:]:  # need at least 1 prior fold of OOF data to train the meta-model on
        meta_train = oof[oof["fold"] < test_fold]
        meta_test = oof[oof["fold"] == test_fold]
        if len(meta_train) < 200 or len(meta_test) < 50:
            continue

        meta_model = LGBMRegressor(n_estimators=100, max_depth=3, learning_rate=0.05,
                                    subsample=0.8, random_state=42, verbosity=-1)
        meta_model.fit(meta_train[meta_features], meta_train[target])
        meta_pred = meta_model.predict(meta_test[meta_features])

        actual = meta_test[target].values
        meta_mae = np.abs(actual - meta_pred).mean()
        base_maes = {f: np.abs(actual - meta_test[f].values).mean() for f in meta_features}

        evaluated_folds += 1
        best_base = min(base_maes, key=base_maes.get)
        base_learner_wins[best_base] = base_learner_wins.get(best_base, 0) + 1
        meta_beats_best_base = meta_mae < base_maes[best_base]
        if meta_beats_best_base:
            meta_wins += 1

        print(f"  Fold {test_fold} (n={len(meta_test)}): "
              f"baseline={base_maes['baseline_pred']:.4f} "
              f"absolute={base_maes['absolute_pred']:.4f} "
              f"relative={base_maes['relative_pred']:.4f}  "
              f"META={meta_mae:.4f}  -> {'META WINS' if meta_beats_best_base else 'a base learner still wins'}")

    print(f"  Meta-model beat the best individual base learner in {meta_wins}/{evaluated_folds} folds")
    print(f"  Best individual base learner by fold: {base_learner_wins}")


def run():
    symbols = get_symbols()
    df = download_data(symbols)
    print(f"{len(df)} rows, date range {df['tradingDate'].min().date()} to {df['tradingDate'].max().date()}")
    block_starts, block_ends = make_blocks(df)

    for h in HORIZONS:
        run_horizon(df, h, block_starts, block_ends)


if __name__ == "__main__":
    run()
