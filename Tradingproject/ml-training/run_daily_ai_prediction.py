"""Generates today's live AI predictions (intraday close + 5/10/20/40-day forward return) for
every instrument and submits them to the backend's ai_predictions ledger. Meant to run once a
day (ideally near/after market open for the intraday call, any time after that for the multi-day
calls since they're based on the latest complete daily snapshot).

Trains on ALL available history each run (not held out - the point here is live deployment, not
another accuracy measurement; accuracy is what the live ledger itself measures going forward, via
GET /api/ai-predictions/{instrument}/rolling-accuracy).

IMPORTANT: the offline walk-forward validation (validate_multiday_walkforward.py) found NO edge
over baseline for any of this. Running this daily does not change that - it starts the live,
honest track record the model will be judged on, exactly as agreed. Do not read a "the model
predicted X" print statement here as any kind of signal to act on.
"""
import json
import os
import urllib.parse
import urllib.request
from datetime import date

import pandas as pd
from lightgbm import LGBMRegressor

API_BASE = "https://jerintradingsignal.duckdns.org"
MODEL_VERSION = "lgbm-v1"
INTRADAY_FEATURES = [
    "hoursSinceOpen", "returnSoFarPct", "volatilitySoFarPct", "rsi14", "emaSpreadPct",
    "bodyPct", "upperWickPct", "lowerWickPct", "last3UpCount", "volumeSoFarRatio",
]
MULTIDAY_REQUIRED_FEATURES = [
    "dailyReturnPct", "gapFromPrevClosePct", "intradayRangePct",
    "emaSpreadPct", "rsi14", "atr14",
    "return5dPct", "return10dPct", "return20dPct", "return40dPct",
    "bodyPct", "upperWickPct", "lowerWickPct",
]
MULTIDAY_FEATURES = MULTIDAY_REQUIRED_FEATURES + ["volumeRatio20d"]
HORIZONS = [5, 10, 20, 40]


def get_json(path):
    with urllib.request.urlopen(f"{API_BASE}{path}", timeout=60) as resp:
        return json.loads(resp.read())


def post_json(path, payload):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(f"{API_BASE}{path}", data=data,
                                  headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def get_symbols():
    basket = get_json("/api/monitor/basket")
    return [row["symbol"] for row in basket]


def already_ran_today():
    """GitHub Actions scheduled runs are best-effort, not guaranteed-on-time - the workflow fires
    several times in a morning window as a safety net against delay. This makes a redundant later
    firing a cheap no-op instead of overwriting an earlier, more meaningful intraday prediction
    with one made near market close."""
    live = get_json("/api/ml/intraday-features/NIFTY/live")
    if live is None:
        return False
    existing = get_json("/api/ai-predictions/NIFTY?horizon=INTRADAY")
    return bool(existing) and existing[0]["targetDate"] == live["tradingDate"]


def train_intraday_model():
    print("Training intraday model on full history...")
    rows = get_json("/api/ml/intraday-features/all")
    df = pd.DataFrame(rows)
    model = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03,
                           subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
    model.fit(df[INTRADAY_FEATURES], df["remainingDriftPct"])
    print(f"  trained on {len(df)} rows")
    return model


def run_intraday_predictions(model, symbols):
    print("\nGenerating live intraday predictions...")
    submitted, skipped = 0, 0
    for sym in symbols:
        live = get_json(f"/api/ml/intraday-features/{urllib.parse.quote(sym)}/live")
        if live is None:
            continue

        # Per-symbol safety net matching already_ran_today()'s NIFTY-based check - if this
        # specific symbol already has today's prediction (e.g. a partial earlier run), don't
        # clobber it with a later, less meaningful one.
        existing = get_json(f"/api/ai-predictions/{urllib.parse.quote(sym)}?horizon=INTRADAY")
        if existing and existing[0]["targetDate"] == live["tradingDate"]:
            skipped += 1
            continue

        row = pd.DataFrame([live])[INTRADAY_FEATURES].apply(pd.to_numeric, errors="coerce")
        predicted_drift = model.predict(row)[0]
        current_price = live["currentPrice"]
        predicted_close = current_price * (1 + predicted_drift / 100)

        post_json("/api/ai-predictions", {
            "instrument": sym,
            "horizon": "INTRADAY",
            "valueType": "PRICE",
            "modelVersion": MODEL_VERSION,
            "predictedValue": round(predicted_close, 4),
            "baselineValue": round(current_price, 4),
            "targetDate": live["tradingDate"],
        })
        submitted += 1
    print(f"  submitted {submitted} intraday predictions, skipped {skipped} (already had today's)")


def train_multiday_models_per_symbol(symbols):
    print("\nDownloading multi-day feature data...")
    all_rows = []
    for sym in symbols:
        rows = get_json(f"/api/ml/features/{urllib.parse.quote(sym)}")
        all_rows.extend(rows)
    df = pd.DataFrame(all_rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])

    models = {}
    baselines = {}
    for h in HORIZONS:
        target = f"forwardReturn{h}dPct"
        sub = df.dropna(subset=MULTIDAY_REQUIRED_FEATURES + [target]).copy()
        model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03,
                               subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
        model.fit(sub[MULTIDAY_FEATURES], sub[target])
        models[h] = model
        baselines[h] = sub[target].mean()
        print(f"  {h}d model trained on {len(sub)} rows, baseline (mean forward return) = {baselines[h]:.4f}%")

    return df, models, baselines


def run_multiday_predictions(df, models, baselines, symbols):
    print("\nGenerating live multi-day predictions...")
    submitted = 0
    for sym in symbols:
        sym_rows = df[df["instrument"] == sym].sort_values("tradingDate")
        if sym_rows.empty:
            continue
        latest = sym_rows.iloc[-1]
        if latest[MULTIDAY_REQUIRED_FEATURES].isnull().any():
            continue  # not enough history yet for this instrument

        target_date = latest["tradingDate"].date().isoformat()
        feature_row = latest[MULTIDAY_FEATURES].to_frame().T.apply(pd.to_numeric, errors="coerce")

        for h in HORIZONS:
            predicted_return = models[h].predict(feature_row)[0]
            post_json("/api/ai-predictions", {
                "instrument": sym,
                "horizon": f"FORWARD_{h}D",
                "valueType": "RETURN_PCT",
                "modelVersion": MODEL_VERSION,
                "predictedValue": round(float(predicted_return), 4),
                "baselineValue": round(float(baselines[h]), 4),
                "targetDate": target_date,
            })
            submitted += 1
    print(f"  submitted {submitted} multi-day predictions")


if __name__ == "__main__":
    print(f"=== AI prediction run: {date.today().isoformat()} ===")
    print("NOTE: offline validation found no edge over baseline for this model. This run starts")
    print("the live, honest track record it will be judged on - not a claim it works.\n")

    if already_ran_today():
        print("NIFTY already has today's INTRADAY prediction - this is a redundant safety-net "
              "firing (GitHub's scheduled runs are best-effort, not guaranteed-on-time). Skipping "
              "the expensive training/prediction steps entirely rather than risk overwriting an "
              "earlier, more meaningful prediction with a late one.")
        raise SystemExit(0)

    symbols = get_symbols()
    print(f"{len(symbols)} instruments: {','.join(symbols)}")

    intraday_model = train_intraday_model()
    run_intraday_predictions(intraday_model, symbols)

    df, multiday_models, baselines = train_multiday_models_per_symbol(symbols)
    run_multiday_predictions(df, multiday_models, baselines, symbols)

    print("\nDone. Evaluate pending predictions later via POST /api/ai-predictions/evaluate-all")
    print("(already wired into the hourly job automatically).")
