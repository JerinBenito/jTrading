"""Generates live AI predictions (intraday close + 5/10/20/40-day forward return) for every
instrument and submits them to the backend's ai_predictions ledger.

Multi-day predictions only need to run once a day (their features/target don't change
intraday). The intraday same-day-close prediction is deliberately different: it's meant to run
several times through the trading session (see QuartzConfig's dispatch schedule, 10:10 IST
through market close), each run re-predicting from a fresh live feature snapshot that includes
more of today's actual price action than the last run had - genuinely refining today's call
with newer information as the day progresses, rather than locking in one static prediction at
market open. Retraining is cheap (seconds, same historical data each time) - what changes
between runs is the live snapshot fed to the trained model at inference time, not the model
itself.

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

import joblib
import pandas as pd
from lightgbm import LGBMRegressor

API_BASE = "https://jerintradingsignal.duckdns.org"
# Traceable to the exact GitHub Actions run that trained it (and whose artifact upload holds the
# actual model file) when running there; "local" when run by hand. See save_models() / item #6.
MODEL_VERSION = f"lgbm-v1-run{os.environ.get('GITHUB_RUN_ID', 'local')}"
MODELS_DIR = os.path.join(os.path.dirname(__file__), "models", date.today().isoformat())
INTRADAY_FEATURES = [
    "hoursSinceOpen", "returnSoFarPct", "volatilitySoFarPct", "rsi14", "emaSpreadPct",
    "bodyPct", "upperWickPct", "lowerWickPct", "last3UpCount", "volumeSoFarRatio",
    # Supplementary features added 2026-09-06 - the deterministic/HMM/GARCH daily calls, options
    # PCR, overnight global market context, company fundamentals, and the most recent pattern
    # signal's track record (see SupplementaryFeatureService). Most of these sources are only
    # days old, so most historical rows will be NaN here for a while - LightGBM handles that
    # natively (same as volumeSoFarRatio always has, for the NIFTY/BANKNIFTY index which has no
    # real volume), it's not a reason to exclude them.
    "deterministicDeviationPct", "hmmDeviationPct", "garchRangeWidthPct", "pcrLatest",
    "globalSp500ChangePct", "globalCrudeOilChangePct", "globalUsdInrChangePct",
    "fundamentalPe", "fundamentalRoe", "recentPatternWinRate", "recentPatternDirection",
]
MULTIDAY_REQUIRED_FEATURES = [
    "dailyReturnPct", "gapFromPrevClosePct", "intradayRangePct",
    "emaSpreadPct", "rsi14", "atr14",
    "return5dPct", "return10dPct", "return20dPct", "return40dPct",
    "bodyPct", "upperWickPct", "lowerWickPct",
]
# Deliberately NOT in MULTIDAY_REQUIRED_FEATURES (the dropna filter) - these are frequently null
# (same treatment volumeRatio20d already got), and requiring them non-null would wipe out nearly
# the entire training set while these data sources are still young.
SUPPLEMENTARY_FEATURES = [
    "volumeRatio20d", "deterministicDeviationPct", "hmmDeviationPct", "garchRangeWidthPct",
    "pcrLatest", "globalSp500ChangePct", "globalCrudeOilChangePct", "globalUsdInrChangePct",
    "fundamentalPe", "fundamentalRoe", "recentPatternWinRate", "recentPatternDirection",
]
MULTIDAY_FEATURES = MULTIDAY_REQUIRED_FEATURES + SUPPLEMENTARY_FEATURES
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


def already_ran_multiday_today():
    """The multi-day (5/10/20/40-day forward) models don't benefit from re-running intraday -
    their features and targets don't change until tomorrow's daily bar closes - so these only
    need one real run per day. The intraday model is deliberately NOT gated by this: it's meant
    to be re-run several times through the trading session (see QuartzConfig's dispatch
    schedule), each time refining today's prediction with more of today's actual price action
    baked into the live feature snapshot, rather than locking in one static call at market
    open and never looking at the rest of the day."""
    live = get_json("/api/ml/intraday-features/NIFTY/live")
    if live is None:
        return False
    existing = get_json("/api/ai-predictions/NIFTY?horizon=FORWARD_5D")
    return bool(existing) and existing[0]["targetDate"] == live["tradingDate"]


def save_model(model, name):
    """Persists the trained model to a real file — see item #6: previously every model was
    retrained from scratch and discarded each run, with no reproducible artifact tied to a
    day's predictions. Saved under models/<date>/ and, on GitHub Actions, uploaded as a build
    artifact by the workflow (ephemeral runner disk otherwise loses it when the job ends)."""
    os.makedirs(MODELS_DIR, exist_ok=True)
    path = os.path.join(MODELS_DIR, f"{name}.joblib")
    joblib.dump(model, path)
    print(f"  saved model -> {path}")
    return path


def train_intraday_model():
    print("Training intraday model on full history...")
    rows = get_json("/api/ml/intraday-features/all")
    df = pd.DataFrame(rows)
    model = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03,
                           subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
    model.fit(df[INTRADAY_FEATURES], df["remainingDriftPct"])
    print(f"  trained on {len(df)} rows")
    save_model(model, "intraday")
    return model


def run_intraday_predictions(model, symbols):
    """Deliberately overwrites today's existing INTRADAY prediction every time this runs (the
    backend upserts by instrument+horizon+day) rather than skipping if one already exists - a
    later call in the same day means a fresher live snapshot with more of today's actual price
    action in it, which is strictly more informative than the earlier call, not a risk of
    clobbering something better."""
    print("\nGenerating live intraday predictions...")
    submitted, skipped = 0, 0
    for sym in symbols:
        live = get_json(f"/api/ml/intraday-features/{urllib.parse.quote(sym)}/live")
        if live is None:
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
    print(f"  submitted {submitted} intraday predictions, skipped {skipped} (no live data yet)")


def train_multiday_models_per_symbol(symbols):
    # ml_feature_snapshots is never refreshed automatically (deliberately manual - see
    # MlFeatureSnapshotController) - without this, training silently anchors off whatever day
    # someone last happened to backfill, which can be days stale (caught 2026-09-07: a Sunday
    # backfill meant Monday's multi-day predictions were still anchored to the prior Friday).
    # Recomputing here means every run - not just a manually-remembered one - anchors off today.
    print("Refreshing ml_feature_snapshots before training...")
    backfill_result = post_json("/api/ml/features/backfill-all", {})
    print(f"  refreshed {backfill_result['instrumentsProcessed']} instruments, "
          f"{backfill_result['totalRowsWritten']} rows written")

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
        # The supplementary features (deterministicDeviationPct, hmmDeviationPct, ...) are new
        # and mostly/entirely null across historical rows, which makes pandas infer dtype
        # "object" for those columns rather than float64 - LightGBM rejects object dtypes
        # outright. Coerce explicitly; NaN is fine (LightGBM handles missing values natively),
        # "object" is not.
        sub[MULTIDAY_FEATURES] = sub[MULTIDAY_FEATURES].apply(pd.to_numeric, errors="coerce")
        model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03,
                               subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
        model.fit(sub[MULTIDAY_FEATURES], sub[target])
        models[h] = model
        baselines[h] = sub[target].mean()
        print(f"  {h}d model trained on {len(sub)} rows, baseline (mean forward return) = {baselines[h]:.4f}%")
        save_model(model, f"forward_{h}d")

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
        anchor_close = float(latest["close"])
        feature_row = latest[MULTIDAY_FEATURES].to_frame().T.apply(pd.to_numeric, errors="coerce")

        for h in HORIZONS:
            predicted_return = float(models[h].predict(feature_row)[0])
            baseline_return = float(baselines[h])
            post_json("/api/ai-predictions", {
                "instrument": sym,
                "horizon": f"FORWARD_{h}D",
                "valueType": "RETURN_PCT",
                "modelVersion": MODEL_VERSION,
                "predictedValue": round(predicted_return, 4),
                "baselineValue": round(baseline_return, 4),
                "targetDate": target_date,
                # Real rupee price converted from the % return using the anchor close - item #7.
                "predictedPrice": round(anchor_close * (1 + predicted_return / 100), 4),
                "baselinePrice": round(anchor_close * (1 + baseline_return / 100), 4),
            })
            submitted += 1
    print(f"  submitted {submitted} multi-day predictions")


if __name__ == "__main__":
    print(f"=== AI prediction run: {date.today().isoformat()} ===")
    print("NOTE: offline validation found no edge over baseline for this model. This run starts")
    print("the live, honest track record it will be judged on - not a claim it works.\n")

    symbols = get_symbols()
    print(f"{len(symbols)} instruments: {','.join(symbols)}")

    # Intraday: always refreshed, every time this script runs (see QuartzConfig - dispatched
    # several times through the trading session, not just once near open). Each run re-trains
    # on the same historical data (cheap, seconds-scale) but predicts from a fresh live feature
    # snapshot that includes more of today's actual price action than the last run had -
    # genuinely re-predicting from newer information, not just re-anchoring the point estimate
    # to the current price the way the deterministic model does.
    intraday_model = train_intraday_model()
    run_intraday_predictions(intraday_model, symbols)

    # Multi-day: only needs one real run per day - its features/target don't change intraday.
    if already_ran_multiday_today():
        print("\nMulti-day models already ran today - skipping (today's intraday refresh above still happened).")
    else:
        df, multiday_models, baselines = train_multiday_models_per_symbol(symbols)
        run_multiday_predictions(df, multiday_models, baselines, symbols)

    print("\nDone. Evaluate pending predictions later via POST /api/ai-predictions/evaluate-all")
    print("(already wired into the hourly job automatically).")
