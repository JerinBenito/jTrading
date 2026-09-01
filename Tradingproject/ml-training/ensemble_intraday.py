"""Ensemble/stacking for the intraday same-day-close prediction - same idea as
ensemble_multiday.py: combine multiple base learners' raw outputs via a trained meta-model,
rather than trusting any single one, with proper out-of-fold generation at both layers.

Base learners:
  1. baseline    - assume current price holds (the existing live baseline)
  2. knn_simple  - k-NN on return-so-far alone (Phase B "simple" analog)
  3. knn_rich    - k-NN on return/volatility/RSI/EMA spread (Phase B "rich" analog)
  4. lgbm        - LightGBM directly on all engineered features (already-tested intraday model)
"""
import json
import urllib.request

import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor
from sklearn.neighbors import KNeighborsRegressor
from sklearn.preprocessing import StandardScaler

API_BASE = "https://jerintradingsignal.duckdns.org"
NUM_BLOCKS = 5
K_NEIGHBORS = 15

SIMPLE_KNN_FEATURES = ["returnSoFarPct"]
RICH_KNN_FEATURES = ["returnSoFarPct", "volatilitySoFarPct", "rsi14", "emaSpreadPct"]
LGBM_FEATURES = [
    "hoursSinceOpen", "returnSoFarPct", "volatilitySoFarPct", "rsi14", "emaSpreadPct",
    "bodyPct", "upperWickPct", "lowerWickPct", "last3UpCount", "volumeSoFarRatio",
]
TARGET = "remainingDriftPct"


def get_json(path):
    with urllib.request.urlopen(f"{API_BASE}{path}", timeout=90) as resp:
        return json.loads(resp.read())


def download_data():
    print("Downloading pooled intraday feature data...")
    rows = get_json("/api/ml/intraday-features/all")
    df = pd.DataFrame(rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    return df


def make_blocks(df):
    dates = sorted(df["tradingDate"].unique())
    edges = np.linspace(0, len(dates), NUM_BLOCKS + 1, dtype=int)
    return [dates[edges[i]] for i in range(NUM_BLOCKS)], [dates[min(edges[i + 1], len(dates) - 1)] for i in range(NUM_BLOCKS)]


def knn_predict(train, test, features):
    scaler = StandardScaler()
    x_train = scaler.fit_transform(train[features])
    x_test = scaler.transform(test[features])
    model = KNeighborsRegressor(n_neighbors=K_NEIGHBORS)
    model.fit(x_train, train[TARGET])
    return model.predict(x_test)


def lgbm_predict(train, test):
    model = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03,
                           subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
    model.fit(train[LGBM_FEATURES], train[TARGET])
    return model.predict(test[LGBM_FEATURES])


def run():
    df = download_data()
    required = list(set(SIMPLE_KNN_FEATURES + RICH_KNN_FEATURES + LGBM_FEATURES + [TARGET]))
    df = df.dropna(subset=[c for c in required if c != "volumeSoFarRatio"])  # volumeSoFarRatio may be NaN (index) - LightGBM tolerates it, KNN doesn't use it
    print(f"{len(df)} rows, date range {df['tradingDate'].min().date()} to {df['tradingDate'].max().date()}")
    block_starts, block_ends = make_blocks(df)

    # ---- Layer 1: out-of-fold base-learner predictions ----
    oof_rows = []
    for fold in range(1, NUM_BLOCKS):
        train = df[df["tradingDate"] < block_starts[fold]]
        test = df[(df["tradingDate"] >= block_starts[fold]) & (df["tradingDate"] <= block_ends[fold])]
        if len(train) < 2000 or len(test) < 500:
            continue
        print(f"Generating fold {fold} base predictions (train={len(train)}, test={len(test)})...")

        fold_df = test[["instrument", "tradingDate", "hoursSinceOpen", TARGET]].copy()
        fold_df["fold"] = fold
        fold_df["baseline_pred"] = 0.0  # baseline predicts zero drift
        fold_df["knn_simple_pred"] = knn_predict(train, test, SIMPLE_KNN_FEATURES)
        fold_df["knn_rich_pred"] = knn_predict(train, test, RICH_KNN_FEATURES)
        fold_df["lgbm_pred"] = lgbm_predict(train, test)
        oof_rows.append(fold_df)

    oof = pd.concat(oof_rows, ignore_index=True)
    meta_features = ["baseline_pred", "knn_simple_pred", "knn_rich_pred", "lgbm_pred"]

    # ---- Layer 2: meta-model, walk-forward validated across the OOF folds ----
    print(f"\n{'=' * 70}\nMeta-model evaluation\n{'=' * 70}")
    fold_ids = sorted(oof["fold"].unique())
    meta_wins, evaluated_folds = 0, 0
    best_base_wins = {f: 0 for f in meta_features}

    for test_fold in fold_ids[1:]:
        meta_train = oof[oof["fold"] < test_fold]
        meta_test = oof[oof["fold"] == test_fold]
        if len(meta_train) < 1000 or len(meta_test) < 500:
            continue

        meta_model = LGBMRegressor(n_estimators=100, max_depth=3, learning_rate=0.05,
                                    subsample=0.8, random_state=42, verbosity=-1)
        meta_model.fit(meta_train[meta_features], meta_train[TARGET])
        meta_pred = meta_model.predict(meta_test[meta_features])

        actual = meta_test[TARGET].values
        meta_mae = np.abs(actual - meta_pred).mean()
        base_maes = {f: np.abs(actual - meta_test[f].values).mean() for f in meta_features}

        evaluated_folds += 1
        best_base = min(base_maes, key=base_maes.get)
        best_base_wins[best_base] += 1
        meta_beats_best = meta_mae < base_maes[best_base]
        if meta_beats_best:
            meta_wins += 1

        print(f"Fold {test_fold} (n={len(meta_test)}): " +
              "  ".join(f"{k.replace('_pred', '')}={v:.4f}" for k, v in base_maes.items()) +
              f"  META={meta_mae:.4f}  -> {'META WINS' if meta_beats_best else 'a base learner still wins'}")

    print(f"\nMeta-model beat the best individual base learner in {meta_wins}/{evaluated_folds} folds")
    print(f"Best individual base learner by fold: {best_base_wins}")


if __name__ == "__main__":
    run()
