"""Does a real ML-learned cross-sectional ranker find anything the six hand-picked features
(tested in rank_and_barrier_backtest.py, all failed) couldn't?

Walk-forward, expanding window, pooled across the whole basket (one shared model, not per-stock -
standard for cross-sectional ranking): for each calendar month, train a LightGBM regressor on
every prior row whose own 5-day-forward label is guaranteed to be fully known before this month
starts (purged - see PURGE_DAYS), predict that month's forward 5-day return for every stock,
then measure the SAME Rank IC statistic used in rank_and_barrier_backtest.py so the two are
directly comparable.
"""
import json
import urllib.request as u
import numpy as np
import pandas as pd
from scipy import stats
from lightgbm import LGBMRegressor

API_BASE = "https://jerintradingsignal.duckdns.org"
FORWARD_TARGET = "forwardReturn5dPct"
PURGE_DAYS = 5  # the label horizon - training rows whose label window could overlap the test month are dropped
MIN_TRAIN_MONTHS = 6

FEATURES = [
    "return5dPct", "return10dPct", "return20dPct", "return40dPct",
    "rsi14", "emaSpreadPct", "atr14", "dailyReturnPct", "gapFromPrevClosePct",
    "intradayRangePct", "volumeRatio20d", "bodyPct", "upperWickPct", "lowerWickPct",
]


def get_json(path):
    with u.urlopen(f"{API_BASE}{path}", timeout=120) as resp:
        return json.loads(resp.read())


def load_all():
    symbols = [r["symbol"] for r in get_json("/api/monitor/basket") if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
    frames = []
    for s in symbols:
        rows = get_json(f"/api/ml/features/{s}")
        if rows:
            df = pd.DataFrame(rows)
            df["instrument"] = s
            frames.append(df)
    df = pd.concat(frames, ignore_index=True)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    return df.sort_values(["instrument", "tradingDate"]).reset_index(drop=True)


def main():
    df = load_all()
    df[FEATURES] = df[FEATURES].apply(pd.to_numeric, errors="coerce")
    df["month"] = df["tradingDate"].dt.to_period("M")
    months = sorted(df["month"].unique())
    print(f"Loaded {len(df)} rows, {df['instrument'].nunique()} instruments, "
          f"{df['tradingDate'].min().date()} to {df['tradingDate'].max().date()}, {len(months)} months")

    daily_ics = []
    trained_months = 0
    for i, test_month in enumerate(months):
        if i < MIN_TRAIN_MONTHS:
            continue
        test_month_start = test_month.start_time
        # purge: drop any training row whose forward-label window could reach into the test month
        purge_cutoff = test_month_start - pd.Timedelta(days=PURGE_DAYS * 2)  # generous purge (calendar days for trading days)
        train = df[(df["tradingDate"] < purge_cutoff) & df[FORWARD_TARGET].notna()]
        train = train.dropna(subset=FEATURES, thresh=max(1, len(FEATURES) // 2))
        test = df[df["month"] == test_month]
        if len(train) < 500 or len(test) < 20:
            continue

        X_train = train[FEATURES]
        y_train = train[FORWARD_TARGET]
        model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.05,
                               subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
        model.fit(X_train, y_train)
        trained_months += 1

        test_valid = test.dropna(subset=[FORWARD_TARGET])
        if test_valid.empty:
            continue
        X_test = test_valid[FEATURES]
        preds = model.predict(X_test)
        test_valid = test_valid.assign(pred=preds)

        for day, group in test_valid.groupby("tradingDate"):
            if len(group) < 10:
                continue
            ic, _ = stats.spearmanr(group["pred"], group[FORWARD_TARGET])
            if not np.isnan(ic):
                daily_ics.append(ic)

    if not daily_ics:
        print("No valid out-of-sample days produced - not enough history.")
        return

    ics = np.array(daily_ics)
    mean_ic = ics.mean()
    std_ic = ics.std()
    t_stat = mean_ic / (std_ic / np.sqrt(len(ics))) if std_ic > 0 else 0
    print(f"\nTrained {trained_months} monthly walk-forward models, {len(ics)} out-of-sample evaluated days")
    print(f"ML ranker Rank IC: mean={mean_ic:+.4f}  std={std_ic:.4f}  ICIR={mean_ic/std_ic if std_ic>0 else 0:+.3f}  t-stat={t_stat:+.2f}")
    print(f"(for comparison: the best single hand-picked feature had t-stat -2.42, and did not survive "
          f"multiple-comparison correction)")


if __name__ == "__main__":
    main()
