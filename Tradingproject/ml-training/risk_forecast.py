"""Next-session RISK forecast for the NIFTY 50 basket: how big will tomorrow's swing be, how bad can it get, and how
likely is a big move - NOT which way the price goes (next-day direction was shown unpredictable: hit rate 51.0%,
rank IC +0.004; next-day range is: rank corr 0.48 vs 0.43 for ATR, paired t=+3.7 over 240 walk-forward days -
vol_forecast_test.py / risk_model_validation.py).

Inputs per stock, chronological COMPLETED days plus (for the prediction row) today's final numbers rebuilt from the
last hourly candle - never the mid-morning daily snapshot. Everything here is descriptive statistics, not advice.

prepare(frames, results_dates)  -> feature table (one row per stock-day, targets = NEXT day's range / |return|)
fit(train)                      -> {'mean', 'q90', 'big'} LightGBM models
predict(models, rows)           -> expected range %, 90th-percentile range %, probability of a >= BIG_MOVE_PCT close-to-close move
"""
import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier, LGBMRegressor

BIG_MOVE_PCT = 2.0
CORP_ACTION_PCT = 25.0
CORP_ACTION_WINDOW = 25
FEATS = ["r1", "r5", "r14", "r20", "absret", "absret5", "volumeRatio20d", "gap", "ret5", "ret20", "mkt_range", "mkt_range5",
         "dow_next", "results_next"]
_PARAMS = dict(n_estimators=300, max_depth=4, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)


def next_business_day(ts):
    ts = pd.Timestamp(ts) + pd.Timedelta(days=1)
    while ts.weekday() >= 5:
        ts += pd.Timedelta(days=1)
    return ts


def prepare(frames, results_dates=None):
    """frames: {symbol: DataFrame[tradingDate, open, close, range, volumeRatio20d, gap]} chronological, the LAST row may be
    the prediction row (today). results_dates: {symbol: iterable of results dates (datetime.date)}."""
    parts = []
    for sym, f in frames.items():
        f = f.sort_values("tradingDate").reset_index(drop=True).copy()
        f["instrument"] = sym
        f["ret"] = (f["close"] / f["close"].shift(1) - 1) * 100
        f["absret"] = f["ret"].abs()
        f["r1"] = f["range"]
        f["r5"] = f["range"].rolling(5, min_periods=5).mean()
        f["r14"] = f["range"].rolling(14, min_periods=14).mean()
        f["r20"] = f["range"].rolling(20, min_periods=20).mean()
        f["absret5"] = f["absret"].rolling(5, min_periods=5).mean()
        f["ret5"] = (f["close"] / f["close"].shift(5) - 1) * 100
        f["ret20"] = (f["close"] / f["close"].shift(20) - 1) * 100
        nxt_date = f["tradingDate"].shift(-1)
        nxt_date.iloc[-1] = next_business_day(f["tradingDate"].iloc[-1])             # prediction row: next session assumed
        f["next_date"] = nxt_date
        f["target_range"] = f["range"].shift(-1)
        f["target_absret"] = f["absret"].shift(-1)
        rd = np.array(sorted(pd.to_datetime(list(results_dates.get(sym, []))).values.astype("datetime64[D]"))) if results_dates else np.array([], dtype="datetime64[D]")
        nd = f["next_date"].values.astype("datetime64[D]")
        f["results_next"] = [float(len(rd) > 0 and np.any((rd <= d) & (d - rd <= np.timedelta64(1, "D")))) for d in nd]
        # unadjusted corporate actions (e.g. Tata Motors' Oct 2025 demerger: fake -36% / -40% steps) are not real swings
        flagged = np.where(f["ret"].abs().to_numpy() > CORP_ACTION_PCT)[0]
        if len(flagged):
            bad = np.zeros(len(f), dtype=bool)
            for k in flagged:
                bad[max(0, k - CORP_ACTION_WINDOW): k + CORP_ACTION_WINDOW + 1] = True
            bad[-1] = False                      # never drop the prediction row
            f = f[~bad]
        parts.append(f)
    d = pd.concat(parts, ignore_index=True)
    mk = d.groupby("tradingDate")["range"].mean()
    d["mkt_range"] = d["tradingDate"].map(mk)
    d["mkt_range5"] = d["tradingDate"].map(mk.rolling(5, min_periods=5).mean())
    d["dow_next"] = pd.to_datetime(d["next_date"]).dt.dayofweek
    return d


def fit(train, feats=None):
    """feats defaults to FEATS; pass FEATS + extra columns (e.g. implied-vol features, see iv_live.py) for the extended model."""
    feats = list(feats or FEATS)
    x = train.dropna(subset=feats + ["target_range", "target_absret"])
    return {
        "feats": feats,
        "mean": LGBMRegressor(**_PARAMS).fit(x[feats], x["target_range"]),
        "q90": LGBMRegressor(objective="quantile", alpha=0.9, **_PARAMS).fit(x[feats], x["target_range"]),
        "big": LGBMClassifier(**_PARAMS).fit(x[feats], (x["target_absret"] >= BIG_MOVE_PCT).astype(int)),
    }


def predict(models, rows):
    feats = models.get("feats", FEATS)
    mean = models["mean"].predict(rows[feats])
    q90 = np.maximum(models["q90"].predict(rows[feats]), mean)
    big = models["big"].predict_proba(rows[feats])[:, 1]
    return mean, q90, big
