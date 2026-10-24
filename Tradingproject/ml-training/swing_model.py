"""Same-day REMAINING-SWING forecast: at each hourly checkpoint, how far (highest high to lowest low, % of the current
price) can a stock still move between now and the close - expected value and a 90th-percentile cushion. Built only from
what the hourly pipeline already has (no minute data needed live). Walk-forward on 600 days (intraday_risk_test.py,
swing_walkforward.py): rank corr 0.64, MAE 21% better than an average guess, and the 90% cushion holds 90.9% of the time.

Training rows come from /api/ml/intraday-features/all (rows carry the target remainingSwingPct and the last candle's range).
"""
import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor

FEATS = ["k", "ret", "rng", "last_rng", "vol_ratio", "prev_rng", "r5", "r20", "gap", "mkt_rng", "results"]
_PARAMS = dict(n_estimators=300, max_depth=4, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
STOCK_EXCLUDE = ("NIFTY", "BANKNIFTY")


def day_table(df):
    """Per (instrument, day): full-day range % (the last hourly row's running range), first-candle open, last close, and the
    prior-day / 5-day / 20-day average ranges and previous close that the features need. One row per instrument-day."""
    d = df.sort_values(["instrument", "tradingDate", "hoursSinceOpen"])
    last = d.groupby(["instrument", "tradingDate"]).tail(1)
    first = d[d["hoursSinceOpen"] == 0]
    t = last[["instrument", "tradingDate", "volatilitySoFarPct", "currentPrice"]].rename(columns={"volatilitySoFarPct": "full_rng", "currentPrice": "close"})
    f = first[["instrument", "tradingDate", "currentPrice", "returnSoFarPct"]].copy()
    f["open"] = pd.to_numeric(f["currentPrice"]) / (1 + pd.to_numeric(f["returnSoFarPct"]) / 100)
    t = t.merge(f[["instrument", "tradingDate", "open"]], on=["instrument", "tradingDate"], how="left")
    t["full_rng"] = pd.to_numeric(t["full_rng"])
    g = t.groupby("instrument")
    t["prev_rng"] = g["full_rng"].shift(1)
    t["r5"] = g["full_rng"].transform(lambda s: s.shift(1).rolling(5, min_periods=5).mean())
    t["r20"] = g["full_rng"].transform(lambda s: s.shift(1).rolling(20, min_periods=20).mean())
    t["prev_close"] = g["close"].shift(1)
    t["gap"] = (t["open"] / t["prev_close"] - 1) * 100
    return t[["instrument", "tradingDate", "prev_rng", "r5", "r20", "gap"]]


def training_frame(df):
    """Feature table for every hourly row with a target (hours 0-5). df needs resultsDayOffset (NaN allowed)."""
    d = df.copy()
    for c in ("returnSoFarPct", "volatilitySoFarPct", "lastCandleRangePct", "volumeSoFarRatio", "remainingSwingPct", "currentPrice"):
        d[c] = pd.to_numeric(d[c], errors="coerce")
    t = day_table(d)
    d = d.merge(t, on=["instrument", "tradingDate"], how="left")
    stocks = d[~d["instrument"].isin(STOCK_EXCLUDE)]
    mk = stocks.groupby(["tradingDate", "hoursSinceOpen"])["volatilitySoFarPct"].mean().rename("mkt_rng").reset_index()
    d = d.merge(mk, on=["tradingDate", "hoursSinceOpen"], how="left")
    out = pd.DataFrame({
        "instrument": d["instrument"], "tradingDate": d["tradingDate"], "k": d["hoursSinceOpen"], "ret": d["returnSoFarPct"],
        "rng": d["volatilitySoFarPct"], "last_rng": d["lastCandleRangePct"], "vol_ratio": d["volumeSoFarRatio"],
        "prev_rng": d["prev_rng"], "r5": d["r5"], "r20": d["r20"], "gap": d["gap"], "mkt_rng": d["mkt_rng"],
        "results": (pd.to_numeric(d.get("resultsDayOffset"), errors="coerce") == 0).astype(float),
        "target": d["remainingSwingPct"],
    })
    return out[(out["k"] <= 5)].dropna(subset=["prev_rng", "r5", "r20", "gap", "mkt_rng", "rng", "last_rng"]).reset_index(drop=True)


def fit(train):
    x = train.dropna(subset=["target"])
    return {"mean": LGBMRegressor(**_PARAMS).fit(x[FEATS], x["target"]),
            "q90": LGBMRegressor(objective="quantile", alpha=0.9, **_PARAMS).fit(x[FEATS], x["target"])}


def predict(models, rows):
    mean = np.maximum(models["mean"].predict(rows[FEATS]), 0.0)
    q90 = np.maximum(models["q90"].predict(rows[FEATS]), mean)
    return mean, q90


def live_rows(live_by_symbol, history, results_offsets=None):
    """Feature rows for the latest live snapshot of each instrument (same definitions as training_frame).
    history: day_table(training df) restricted to each instrument's most recent day, plus the last close - see history_for_live()."""
    rows = []
    stock_rng = [float(v["volatilitySoFarPct"]) for k, v in live_by_symbol.items() if k not in STOCK_EXCLUDE and v.get("volatilitySoFarPct") is not None]
    mkt = float(np.mean(stock_rng)) if stock_rng else np.nan
    for sym, v in live_by_symbol.items():
        h = history.get(sym)
        if h is None or v.get("lastCandleRangePct") is None or v.get("volatilitySoFarPct") is None or int(v["hoursSinceOpen"]) > 5:
            continue
        price, ret = float(v["currentPrice"]), float(v["returnSoFarPct"])
        open_ = price / (1 + ret / 100)
        rows.append({"instrument": sym, "k": int(v["hoursSinceOpen"]), "ret": ret, "rng": float(v["volatilitySoFarPct"]),
                     "last_rng": float(v["lastCandleRangePct"]), "vol_ratio": np.nan if v.get("volumeSoFarRatio") is None else float(v["volumeSoFarRatio"]),
                     "prev_rng": h["prev_rng"], "r5": h["r5"], "r20": h["r20"], "gap": (open_ / h["prev_close"] - 1) * 100, "mkt_rng": mkt,
                     "results": float((results_offsets or {}).get(sym) == 0), "currentPrice": price, "hoursSinceOpen": int(v["hoursSinceOpen"]),
                     "tradingDate": v["tradingDate"]})
    return pd.DataFrame(rows)


def history_for_live(df):
    """{instrument: {prev_rng, r5, r20, prev_close}} as of the LAST COMPLETED day in df (the day before the live one)."""
    d = df.copy()
    d["volatilitySoFarPct"] = pd.to_numeric(d["volatilitySoFarPct"], errors="coerce")
    d["currentPrice"] = pd.to_numeric(d["currentPrice"], errors="coerce")
    d = d.sort_values(["instrument", "tradingDate", "hoursSinceOpen"])
    last = d.groupby(["instrument", "tradingDate"]).tail(1)
    out = {}
    for sym, g in last.groupby("instrument"):
        rngs = g["volatilitySoFarPct"].to_numpy(float)
        if len(rngs) >= 20:
            out[sym] = {"prev_rng": float(rngs[-1]), "r5": float(rngs[-5:].mean()), "r20": float(rngs[-20:].mean()), "prev_close": float(g["currentPrice"].iloc[-1])}
    return out
