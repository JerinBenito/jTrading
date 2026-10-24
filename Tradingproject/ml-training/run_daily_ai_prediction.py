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
import gzip
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone

import joblib
import numpy as np
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
    # Added 2026-09-17, per an explicit request that the model see its own recent error rather
    # than only market data - the way an ARIMA model's moving-average term uses past forecast
    # errors. All three describe the PRIOR day (strictly before the one being predicted, computed
    # server-side in SupplementaryFeatureService) - null until at least one prior day has been
    # evaluated, and null entirely for any instrument/day before this field started being
    # recorded (2026-09-17), so expect heavy NaN for a while, same treatment as every other
    # supplementary feature above.
    "aiPriorDayErrorPct", "aiPriorDayDirectionCorrect", "aiPriorDayRevisionGradientPct",
    # Added 2026-09-18: the FIRST call's signed error (the fair, least-information call - positive
    # means the morning call was too high) read from the stored per-call errors. The revision
    # gradient above is now signed too (was magnitude only, which threw away the direction).
    "aiPriorDayFirstCallErrorPct",
    # Added 2026-09-19: the AI's view of its OWN earlier calls today, from the stored per-call
    # table - what it nudged away from the current price at the open and last time, and how much it
    # revised in between. Only calls from strictly earlier hourly checkpoints are visible (a call
    # never sees itself), and the same rule builds the training rows, so training matches the live
    # view. Per-call snapshots only exist from 2026-09-17, so nearly every historical row is NaN.
    "aiTodayFirstNudgePct", "aiTodayLastNudgePct", "aiTodayLastStepPct", "aiTodayLastDriftFromFirstPct",
    # Added 2026-09-28: India VIX's own level (not daily change - backtested and only the level
    # showed a real, corrected-for-multiple-comparisons relationship to NIFTY's forward return,
    # and the same effect held up in individual basket stocks too, 27/50 significant at p<0.05).
    # Brand new data source as of today - same 30-day history gate as every other young feature,
    # non-negotiable after the memorization bug this session already found once.
    "globalIndiaVixLevel",
    # Added 2026-09-29: cross-sectional relative-volume rank - see add_basket_volume_rank(). Not
    # history-gated: derived from volumeSoFarRatio, which already has ~2 years of real data.
    "basketVolumeRankPct",
    # Added 2026-09-29: yesterday's version of the same rank - see add_prev_day_volume_rank().
    # Same reasoning: not history-gated, derived from long-history volume data.
    "prevDayVolumeRankPct",
    # Added 2026-09-29: returnSoFarPct x volumeSoFarRatio - does today's direction-so-far,
    # combined with how busy today has been so far, predict the REST of today's move? Real and
    # strong by mid-afternoon (hour 5: Spearman -0.095, t=-14.99, p<0.0001) - same reversal
    # direction as the daily OBV and 40-day climax-reversal findings: a move up on heavy volume
    # tends to give some back into the close, not extend further. See intraday_obv_backtest.py.
    "intradayObv",
    # Added 2026-10-06: trading days from the stock's quarterly results date (-3..+3, NaN otherwise) -
    # see add_results_offset(). A calibration fix, not a direction signal: on results day the model's
    # 90% range covered only 76% of outcomes without it, 85.5% with it (results_window_ablation.py;
    # pinball +5.5% on those rows, ~0 elsewhere). Not history-gated - the dates span the full history.
    "resultsDayOffset",
]
# The eight inputs that describe the AI's own past errors, nudges and revisions.
AI_SELF_FEATURES = [
    "aiPriorDayErrorPct", "aiPriorDayDirectionCorrect", "aiPriorDayRevisionGradientPct",
    "aiPriorDayFirstCallErrorPct",
    "aiTodayFirstNudgePct", "aiTodayLastNudgePct", "aiTodayLastStepPct", "aiTodayLastDriftFromFirstPct",
]
# The other inputs whose data sources are only weeks old (as of 2026-09-21: 10-24 days each).
YOUNG_SUPPLEMENTARY_FEATURES = [
    "deterministicDeviationPct", "hmmDeviationPct", "garchRangeWidthPct", "pcrLatest",
    "globalSp500ChangePct", "globalCrudeOilChangePct", "globalUsdInrChangePct",
    "recentPatternWinRate", "recentPatternDirection",
    "globalIndiaVixLevel",
]
# All of these are still recorded and served every day, but a feature only enters the FIT once it
# has real history. Found 2026-09-21: with 1-13 days behind them, the AI-error inputs took 8% of
# LightGBM's splits and steered most of the size of every prediction (GARCH, with 10 days, was the
# largest single driver of NIFTY's call) - a feature that is constant within a day and has a handful
# of distinct days lets trees isolate those specific days and fit their outcomes, and since every
# stock moves together on a given day, 1-2 days is really 1-2 observations. That is memorisation,
# not a pattern. (Fundamentals are not gated: they are constants per instrument over the whole
# history, so they say which instrument a row is, not which day.)
HISTORY_GATED_FEATURES = AI_SELF_FEATURES + YOUNG_SUPPLEMENTARY_FEATURES
# Raised 30 -> 120 on 2026-10-14. These inputs are constant within a day (one value per stock per day), so
# 30 days of history is 30 independent observations however many rows there are. Replaying production as of each
# recent day (lean_asof_features.py) showed that once pcrLatest / recentPattern* / deterministicDeviationPct
# passed the 30-day gate they let the trees recognise "which recent days this looks like": 74-86% of hour-0
# calls predicted DOWN on recent days versus 28-58% without them, with no accuracy gain (error 0.5933% with vs
# 0.5897% without, direction 49.7% vs 52.0%, rank IC -0.006 vs +0.056). Live calls leaned down 84-98% of the
# time since 1 Oct for exactly this reason. 120 days (~6 months) is the new bar; re-run the ablation when a
# feature is about to be admitted. NOT the cause (tested, ruled out): the first-hour reversal logic,
# partial-candle volume, the market regime, the hour-median centring.
MIN_HISTORY_DAYS = 120
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
    # Added 2026-09-28, same treatment as its SP500/CRUDE_OIL/USD_INR siblings above (this model
    # has no history-gating mechanism, unlike the intraday model's HISTORY_GATED_FEATURES - not
    # adding one just for this feature would be inconsistent with how those three are already
    # handled here). Directly backtested against this exact target (forwardReturn5dPct/10dPct,
    # what FORWARD_5D/FORWARD_10D predict) - not an extrapolation from a different horizon, unlike
    # its inclusion in the intraday model's same-day-close target.
    "globalIndiaVixLevel",
    # Added 2026-09-29: does a big price move on high volume reverse more than the same move on
    # low volume? Real, published effect (Lee & Swaminathan 2000) and confirmed on our own data
    # at the 40-day horizon (high-volume big moves reversed +0.60% on average vs -0.16% for
    # low-volume big moves, t=+2.49, p=0.013 for the difference) - NOT yet confirmed at 20 days
    # (see volume_climax_reversal_test.py), so this is really evidenced for FORWARD_40D
    # specifically, included here for all horizons since the model already trains separately per
    # horizon and can learn to ignore it where it doesn't help. Signed so the model can read
    # direction: large positive = big up-move on high volume (literature says: expect pullback),
    # large negative = big down-move on high volume (expect bounce).
    "bigMoveVolumeInteraction",
    # Added 2026-09-29: On-Balance Volume 10-day momentum - see obv_momentum() above for the
    # real backtest numbers and the honest sign-flip finding.
    "obvMomentum10d",
]
MULTIDAY_FEATURES = MULTIDAY_REQUIRED_FEATURES + SUPPLEMENTARY_FEATURES


def select_multiday_features(df):
    """Same history gate as the same-day model (select_intraday_features), applied 2026-10-14. The young
    day-constant inputs (pcrLatest, deterministicDeviationPct, hmm/garch, global*, recentPattern*, VIX) are one value
    per stock per day, so ~35 days behind them is ~35 observations. Replayed as-of each recent day
    (multiday_asof_test.py) they made 80% (5d) / 66% (10d) of predictions fall BELOW the training mean (mean -0.78%
    vs +0.13% without them): the same recent-regime fingerprint that gave the same-day model its down-lean. In that
    falling window the lean even scored better on error (4.4% vs -1.3% against the baseline), which is exactly the
    luck a regime fingerprint gets - it flips against you when the market turns. Re-run the ablation before each is
    admitted. Returns (kept, {feature: days_of_history} for the held-out ones)."""
    kept, gated = [], {}
    for f in MULTIDAY_FEATURES:
        if f in HISTORY_GATED_FEATURES and f in df.columns:
            days = int(df.loc[pd.to_numeric(df[f], errors="coerce").notna(), "tradingDate"].nunique())
            if days < MIN_HISTORY_DAYS:
                gated[f] = days
                continue
        kept.append(f)
    return kept, gated
HORIZONS = [5, 10, 20, 40]


def get_json(path, timeout=60, attempts=3):
    """Retries on timeouts/connection errors/5xx. Found 2026-10-05: /api/ml/intraday-features/all
    is ~181 MB and takes ~84 s end to end, against the old fixed 60 s timeout - it only passed by
    a thin margin, and when the 1 GB backend VM stalled for 47 s (thread starvation, swapping)
    the first run of the day timed out and crashed, losing every stock's 10:10 call. The caller
    passes a much longer timeout for that one big download; everything else keeps 60 s."""
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            req = urllib.request.Request(f"{API_BASE}{path}", headers={"Accept-Encoding": "gzip"})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read()
                if resp.headers.get("Content-Encoding") == "gzip":
                    body = gzip.decompress(body)
                return json.loads(body)
        except urllib.error.HTTPError as e:
            if e.code < 500:
                raise
            last_error = e
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last_error = e
        if attempt < attempts:
            wait = 5 * attempt
            print(f"  GET {path} failed ({last_error!r}), retry {attempt}/{attempts - 1} in {wait}s")
            time.sleep(wait)
    raise last_error


def post_json(path, payload, timeout=300):
    # /api/ml/features/backfill-all alone measured ~2m11s for all 52 instruments;
    # 30s was too short and caused a real TimeoutError in production (2026-09-07).
    data = json.dumps(payload).encode()
    req = urllib.request.Request(f"{API_BASE}{path}", data=data,
                                  headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
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


# Targets a 90% interval, matching the deterministic model's real backtested range coverage
# (~90%) so the two are judged on comparable terms.
QUANTILE_LOW_ALPHA = 0.05
QUANTILE_HIGH_ALPHA = 0.95


def add_basket_volume_rank(df):
    """Cross-sectional rank of volumeSoFarRatio among every basket stock at the SAME hour of
    the SAME day - not a stock vs its own history (that's volumeSoFarRatio itself), but a stock
    vs its 49 peers right now. Backtested 2026-09-29: high relative volume at hour 1 predicts a
    genuinely bigger remaining move that day (Rank IC +0.079, t=+11.35, 495 real days) - real,
    strong, and derived from volume data that already has ~2 years of history, so unlike VIX
    this doesn't need a fresh-start history gate."""
    df["basketVolumeRankPct"] = df.groupby(["tradingDate", "hoursSinceOpen"])["volumeSoFarRatio"].rank(pct=True)
    return df


def add_prev_day_volume_rank(df):
    """Yesterday's END-OF-DAY basketVolumeRankPct (this stock's full-day relative volume vs its
    49 peers, from the last trading day) attached to every row of TODAY - known before today's
    market even opens, unlike basketVolumeRankPct itself which only builds up through the day.
    Backtested 2026-09-29 (volume_rank_persistence_test.py): real volume clustering, t=+66 across
    23,570 real (stock, day) pairs - stocks in the top 20% by relative volume yesterday were in
    the top 20% again today 40% of the time, vs a 21% unconditional baseline. tradingDate is an
    ISO string (YYYY-MM-DD), which sorts chronologically the same as lexicographically, so no
    datetime conversion is needed here."""
    day_end = (df.sort_values(["instrument", "tradingDate", "hoursSinceOpen"])
                 .groupby(["instrument", "tradingDate"])["basketVolumeRankPct"].last()
                 .reset_index().rename(columns={"basketVolumeRankPct": "dayEndVolumeRankPct"}))
    day_end = day_end.sort_values(["instrument", "tradingDate"])
    day_end["prevDayVolumeRankPct"] = day_end.groupby("instrument")["dayEndVolumeRankPct"].shift(1)
    return df.merge(day_end[["instrument", "tradingDate", "prevDayVolumeRankPct"]],
                     on=["instrument", "tradingDate"], how="left")


RESULTS_WINDOW_DAYS = 3


def fetch_results_dates(symbols):
    """{symbol: [(date, eps_estimate_or_None), ...]} of quarterly results dates (past and announced/
    expected future) from Yahoo via yfinance. Best-effort by design: any failure returns what was
    gathered so far (possibly {}), and the model simply trains and predicts without the feature - a
    missing calendar must never cost a day's predictions."""
    try:
        import yfinance as yf
    except ImportError:
        print("  yfinance not installed - running without the results calendar")
        return {}
    out = {}
    for sym in symbols:
        if sym in ("NIFTY", "BANKNIFTY"):
            continue
        for attempt in range(2):
            try:
                ed = yf.Ticker(f"{sym}.NS").get_earnings_dates(limit=25)
                if ed is not None and len(ed):
                    ed = ed.reset_index()
                    dates = pd.to_datetime(ed["Earnings Date"].astype(str).str[:10])
                    est = pd.to_numeric(ed["EPS Estimate"], errors="coerce")
                    out[sym] = sorted({(d.date(), None if pd.isna(e) else float(e)) for d, e in zip(dates, est)})
                break
            except Exception as e:  # noqa: BLE001 - vendor errors are many and all non-fatal here
                if attempt == 1:
                    print(f"  results dates for {sym} unavailable ({type(e).__name__})")
                else:
                    time.sleep(2)
        time.sleep(0.3)
    print(f"  results calendar: {len(out)} of {len(symbols)} symbols")
    return out


def results_day_offset(day, event_dates):
    """Business-day offset of `day` from the nearest results date within +-RESULTS_WINDOW_DAYS, else NaN.
    Business days (weekends only, holidays ignored) so training rows and a live row - whose future
    trading calendar is unknown - are computed identically. Offset 0 = the results date itself,
    +1 = the next session (where after-close announcements, ~2/3 of them, actually land)."""
    d = np.datetime64(str(day)[:10], "D")
    best = None
    for e in event_dates:
        e64 = np.datetime64(e, "D")
        k = int(np.busday_count(e64, d)) if d >= e64 else -int(np.busday_count(d, e64))
        if abs(k) <= RESULTS_WINDOW_DAYS and (best is None or abs(k) < abs(best)):
            best = k
    return float(best) if best is not None else np.nan


def add_results_offset(df, results):
    """resultsDayOffset for every training row (NaN for stocks with no calendar)."""
    offsets = {}
    for sym, items in results.items():
        events = [d for d, _ in items]
        for day in df.loc[df["instrument"] == sym, "tradingDate"].unique():
            offsets[(sym, day)] = results_day_offset(day, events)
    df["resultsDayOffset"] = [offsets.get((s, d), np.nan) for s, d in zip(df["instrument"], df["tradingDate"])]
    return df


def push_results_calendar(results):
    """Hands the calendar to the backend for the app's 'results today / soon' flag (best-effort).
    Only the last ~120 days onwards - older dates are history the app does not show."""
    cutoff = date.today().toordinal() - 900
    payload = [{"instrument": sym,
                "dates": [{"resultsDate": d.isoformat(), "epsEstimate": e} for d, e in items if d.toordinal() >= cutoff]}
               for sym, items in results.items()]
    if not payload:
        return
    try:
        res = post_json("/api/results-calendar", payload)
        print(f"  pushed results calendar to the backend: {res}")
    except Exception as e:  # noqa: BLE001
        print(f"  could not push results calendar ({e!r}) - app flag may be stale, predictions unaffected")


CONFORMAL_DAYS = 60
CONFORMAL_COVERAGE = 0.9


def conformal_range_scales(df, features):
    """Per-hour range-widening factors, added 2026-10-14 after the live 90% ranges held the close only 86.9% (first
    call) / 78.2% (last call) of the time. Split-conformal calibration (range_calibration_test.py): fit the same
    low/high quantile models on all days EXCEPT the last CONFORMAL_DAYS, score those held-out days by how far the
    close fell outside the range as a fraction of the range width, and take the per-hour 90% quantile s_h. A live
    range [lo, hi] becomes [lo - s_h*(hi-lo), hi + s_h*(hi-lo)] (s_h may be slightly negative = shrink).
    Walk-forward over 240 days: coverage 86.4% -> 88.2%, pinball slightly better, ranges ~6% wider; a 120-day window
    did not help (volatility drifts), so the window stays recent. Width-relative so it transfers to the indices.
    Returns {} (no change) if there is too little history."""
    days = sorted(df["tradingDate"].unique())
    if len(days) < CONFORMAL_DAYS + 120:
        return {}
    cal_start = days[-CONFORMAL_DAYS]
    cal_train = df[df["tradingDate"] < cal_start]
    cal = df[(df["tradingDate"] >= cal_start) & ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])]
    hour_median = cal_train.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    target = cal_train["remainingDriftPct"] - cal_train["hoursSinceOpen"].map(hour_median)
    preds = []
    for alpha in (QUANTILE_LOW_ALPHA, QUANTILE_HIGH_ALPHA):
        mdl = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8,
                            random_state=42, verbosity=-1, objective="quantile", alpha=alpha)
        mdl.fit(cal_train[features], target)
        preds.append(mdl.predict(cal[features]))
    lo, hi = np.minimum(*preds), np.maximum(*preds)
    y = cal["remainingDriftPct"].to_numpy()
    score = np.maximum(lo - y, y - hi) / np.maximum(hi - lo, 1e-6)
    scales = {}
    for hour, grp in pd.Series(score).groupby(cal["hoursSinceOpen"].to_numpy()):
        n = len(grp)
        scales[int(hour)] = float(np.quantile(grp, min(1.0, np.ceil((n + 1) * CONFORMAL_COVERAGE) / n)))
    return scales


CORP_ACTION_GAP_PCT = 25.0
CORP_ACTION_WINDOW_DAYS = 45


def drop_corporate_action_windows(df, flagged, window=CORP_ACTION_WINDOW_DAYS):
    """Drops, per instrument, every row within `window` trading days of a flagged day. Upstox adjusts candles for splits and
    bonuses but NOT for demergers: Tata Motors' Oct 2025 demerger shows as fake overnight steps of about -36% and -40% (TMPV),
    which would otherwise enter as real returns - squared-error models (multi-day) in particular get dragged by ~40 such rows
    per horizon. `flagged` is {instrument: [dates]}. Returns (filtered df, number of rows dropped)."""
    if not flagged:
        return df, 0
    keep = np.ones(len(df), dtype=bool)
    for inst, days in flagged.items():
        idx = np.where(df["instrument"].to_numpy() == inst)[0]
        if len(idx) == 0:
            continue
        trading_days = np.array(sorted(pd.unique(df["tradingDate"].iloc[idx])))
        pos = {d: i for i, d in enumerate(trading_days)}
        bad = set()
        for d in days:
            if d in pos:
                bad.update(trading_days[max(0, pos[d] - window): pos[d] + window + 1])
        if bad:
            keep[idx[df["tradingDate"].iloc[idx].isin(bad).to_numpy()]] = False
    return df[keep], int((~keep).sum())


def corporate_action_days_intraday(df):
    """{instrument: [days]} where the overnight gap (first hourly candle's open vs the previous day's last close) exceeds
    CORP_ACTION_GAP_PCT - an unadjusted corporate action rather than a real move."""
    d = df[["instrument", "tradingDate", "hoursSinceOpen", "currentPrice", "returnSoFarPct"]].copy()
    d["currentPrice"] = pd.to_numeric(d["currentPrice"], errors="coerce")
    d["returnSoFarPct"] = pd.to_numeric(d["returnSoFarPct"], errors="coerce")
    d = d.sort_values(["instrument", "tradingDate", "hoursSinceOpen"])
    last = d.groupby(["instrument", "tradingDate"])["currentPrice"].last().reset_index().rename(columns={"currentPrice": "lastClose"})
    last["prevClose"] = last.groupby("instrument")["lastClose"].shift(1)
    first = d[d["hoursSinceOpen"] == 0].copy()
    first["open"] = first["currentPrice"] / (1 + first["returnSoFarPct"] / 100)
    m = first.merge(last[["instrument", "tradingDate", "prevClose"]], on=["instrument", "tradingDate"])
    m["gap"] = (m["open"] / m["prevClose"] - 1) * 100
    out = {}
    for inst, grp in m[m["gap"].abs() > CORP_ACTION_GAP_PCT].groupby("instrument"):
        out[inst] = list(grp["tradingDate"])
    return out


def train_intraday_model(results=None):
    print("Training intraday model on full history...")
    rows = get_json("/api/ml/intraday-features/all", timeout=420)
    df = pd.DataFrame(rows)
    df = add_basket_volume_rank(df)
    df = add_prev_day_volume_rank(df)
    df = add_results_offset(df, results or {})
    df, dropped = drop_corporate_action_windows(df, corporate_action_days_intraday(df))
    if dropped:
        print(f"  dropped {dropped} intraday rows within {CORP_ACTION_WINDOW_DAYS} days of an unadjusted corporate action (demerger)")
    df["intradayObv"] = pd.to_numeric(df["returnSoFarPct"], errors="coerce") * \
        pd.to_numeric(df["volumeSoFarRatio"], errors="coerce")
    # aiPriorDay* (added 2026-09-17) is 100% null for every row before that date - with no
    # non-null value anywhere to infer from, pandas types an all-null column as object, which
    # LightGBM rejects outright (same class of bug already hit and fixed on the multi-day path).
    df[INTRADAY_FEATURES] = df[INTRADAY_FEATURES].apply(pd.to_numeric, errors="coerce")
    features, gated = select_intraday_features(df)
    if gated:
        print("  held out of the fit until they have "
              f"{MIN_HISTORY_DAYS} days of history (still recorded): "
              + ", ".join(f"{f} ({days}d)" for f, days in gated.items()))
    print(f"  fitting on {len(features)} of {len(INTRADAY_FEATURES)} features")

    # Three quantile-regression models, same features and hyperparameters (never separately
    # tuned for this - inherited as-is from the original point model), differing only in which
    # quantile of the outcome each is trained to predict. The median model IS the point
    # prediction now (2026-09-23, per an explicit request to unify the two, so the point call and
    # its range can never disagree - both come from the same model family instead of two
    # unrelated fits). The low/high models give a genuinely LEARNED range around it - narrower or
    # wider depending on what the model has seen for similar situations, not a fixed formula.
    # Added 2026-10-12: fit on the remaining drift MINUS each hour's median drift, and do not add it back.
    # The basket drifted slightly down over the training window (median remaining drift about -0.02 to
    # -0.04% mid-session), which parked the median model below zero for almost every stock - from 1 Oct
    # 86-98% of first calls said "down", uncorrelated with the day's real move (-0.08). Centring the
    # target removes that baked-in lean at no measured accuracy cost (lean_ablation.py: 240 test days,
    # pinball p=0.83, MAE p=0.76, share of down calls 80% -> 60%). A sample's average drift is not a forecast.
    hour_median = df.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    target = df["remainingDriftPct"] - df["hoursSinceOpen"].map(hour_median)
    print("  centring target by hour (train-set median remaining drift %): "
          + ", ".join(f"h{int(h)} {v:+.3f}" for h, v in hour_median.items()))
    models = {}
    for name, alpha in (("low", QUANTILE_LOW_ALPHA), ("median", 0.5), ("high", QUANTILE_HIGH_ALPHA)):
        m = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03,
                           subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1,
                           objective="quantile", alpha=alpha)
        m.fit(df[features], target)
        save_model(m, f"intraday_{name}")
        models[name] = m
    print(f"  trained on {len(df)} rows")

    # Each instrument's most recent recorded end-of-day basketVolumeRankPct (the last hour of
    # the last day already in history) - exactly what "yesterday" means for a live prediction
    # made today, since today itself isn't in this historical set yet.
    latest_day_end_rank = (df.sort_values(["instrument", "tradingDate", "hoursSinceOpen"])
                              .groupby("instrument")["basketVolumeRankPct"].last())
    conformal = conformal_range_scales(df, features)
    if conformal:
        print("  conformal range widening by hour (fraction of range width): "
              + ", ".join(f"h{h} {v:+.3f}" for h, v in sorted(conformal.items())))
    swing = None
    try:
        import swing_model as sm
        sw_models = sm.fit(sm.training_frame(df))
        swing = {"models": sw_models, "history": sm.history_for_live(df)}
        print("  trained the same-day remaining-swing models")
    except Exception as e:  # noqa: BLE001 - a risk-forecast failure must never cost a day's price predictions
        print(f"  remaining-swing model skipped: {e!r}")
    return models, latest_day_end_rank, conformal, swing


def select_intraday_features(df):
    """The features the intraday model may train on, and the young inputs held back (with how many
    distinct trading days of data each actually has). Everything outside HISTORY_GATED_FEATURES
    always trains, exactly as before; a gated input is admitted the day it reaches MIN_HISTORY_DAYS,
    with no further change needed."""
    kept, gated = [], {}
    for f in INTRADAY_FEATURES:
        if f == "resultsDayOffset" and (f not in df.columns or df[f].notna().sum() == 0):
            continue  # no results calendar this run (vendor down / yfinance missing): fit without it
        if f in HISTORY_GATED_FEATURES:
            days = int(df.loc[df[f].notna(), "tradingDate"].nunique())
            if days < MIN_HISTORY_DAYS:
                gated[f] = days
                continue
        kept.append(f)
    return kept, gated


def live_feature_row(live, model):
    """One live row shaped exactly like the model was trained: the feature list comes from the
    trained model itself, never from INTRADAY_FEATURES, so a gated feature can't be fed to a model
    that was fit without it (or a re-admitted one missed)."""
    names = list(model.booster_.feature_name())
    return pd.DataFrame([live])[names].apply(pd.to_numeric, errors="coerce")


def push_remaining_swing(swing, live_by_symbol):
    """Same-day remaining-swing forecast (swing_model.py) for every instrument with a live row, pushed to /api/intraday-risk.
    Best-effort: never raises."""
    try:
        import swing_model as sm
        offsets = {sym: (v.get("resultsDayOffset")) for sym, v in live_by_symbol.items()}
        rows = sm.live_rows(live_by_symbol, swing["history"], offsets)
        if rows.empty:
            return
        mean, q90 = sm.predict(swing["models"], rows)
        payload = [{"instrument": r["instrument"], "targetDate": r["tradingDate"], "hoursSinceOpen": int(r["hoursSinceOpen"]),
                    "currentPrice": round(float(r["currentPrice"]), 4), "expectedSwingPct": round(float(mean[i]), 4),
                    "upper90SwingPct": round(float(q90[i]), 4), "modelVersion": MODEL_VERSION} for i, r in rows.iterrows()]
        post_json("/api/intraday-risk", payload)
        print(f"  pushed {len(payload)} same-day remaining-swing forecasts")
    except Exception as e:  # noqa: BLE001
        print(f"  remaining-swing push skipped: {e!r}")


def run_intraday_predictions(models, symbols, latest_day_end_rank, results=None, conformal=None, swing=None):
    """Deliberately overwrites today's existing INTRADAY prediction every time this runs (the
    backend upserts by instrument+horizon+day) rather than skipping if one already exists - a
    later call in the same day means a fresher live snapshot with more of today's actual price
    action in it, which is strictly more informative than the earlier call, not a risk of
    clobbering something better."""
    print("\nGenerating live intraday predictions...")

    # First pass: fetch every symbol's live snapshot before predicting any of them, so
    # basketVolumeRankPct can be computed cross-sectionally (this stock's volumeSoFarRatio vs
    # the other 49, right now) - the same real-time comparison the backtest validated, not each
    # stock's own history in isolation.
    live_by_symbol = {}
    for sym in symbols:
        live = get_json(f"/api/ml/intraday-features/{urllib.parse.quote(sym)}/live")
        if live is not None:
            live_by_symbol[sym] = live

    # Every symbol needs the key present (even as None) - live_feature_row selects columns by
    # name, and a missing key (not just a null value) raises a KeyError.
    for sym, live in live_by_symbol.items():
        live["basketVolumeRankPct"] = None
        rank = latest_day_end_rank.get(sym)
        live["prevDayVolumeRankPct"] = float(rank) if pd.notna(rank) else None
        return_so_far = live.get("returnSoFarPct")
        volume_so_far = live.get("volumeSoFarRatio")
        live["intradayObv"] = return_so_far * volume_so_far if return_so_far is not None and volume_so_far is not None else None
        offset = results_day_offset(live["tradingDate"], [d for d, _ in (results or {}).get(sym, [])])
        live["resultsDayOffset"] = None if np.isnan(offset) else offset
    ratios = {s: v["volumeSoFarRatio"] for s, v in live_by_symbol.items() if v.get("volumeSoFarRatio") is not None}
    if ratios:
        ranks = pd.Series(ratios).rank(pct=True)
        for sym, rank in ranks.items():
            live_by_symbol[sym]["basketVolumeRankPct"] = float(rank)

    if swing is not None:
        push_remaining_swing(swing, live_by_symbol)

    submitted, skipped = 0, len(symbols) - len(live_by_symbol)
    for sym, live in live_by_symbol.items():
        # All three models were trained on the identical feature set this run, so any one of
        # them shapes the row correctly.
        row = live_feature_row(live, models["median"])
        current_price = live["currentPrice"]
        drifts = {name: m.predict(row)[0] for name, m in models.items()}
        # The three models are fit independently, so on a given call there's a small chance the
        # "low" model's output lands above "median" or "high" (quantile crossing) - sort rather
        # than assume it can't happen, so the recorded range is always internally consistent.
        low_drift, median_drift, high_drift = sorted(drifts.values())
        # clamp to [-0.3, +0.3]: the walk-forward never needed more than +0.25, and an uncapped +0.50 (hour 5, latest
        # 60-day window) would double the range on one volatile stretch
        scale = min(0.3, max(-0.3, (conformal or {}).get(int(live.get("hoursSinceOpen") or 0), 0.0)))   # see conformal_range_scales
        width = high_drift - low_drift
        low_drift, high_drift = low_drift - scale * width, high_drift + scale * width
        predicted_close = current_price * (1 + median_drift / 100)
        range_low = current_price * (1 + low_drift / 100)
        range_high = current_price * (1 + high_drift / 100)

        post_json("/api/ai-predictions", {
            "instrument": sym,
            "horizon": "INTRADAY",
            "valueType": "PRICE",
            "modelVersion": MODEL_VERSION,
            "predictedValue": round(predicted_close, 4),
            "baselineValue": round(current_price, 4),
            "targetDate": live["tradingDate"],
            "predictedRangeLow": round(range_low, 4),
            "predictedRangeHigh": round(range_high, 4),
        })
        submitted += 1
    print(f"  submitted {submitted} intraday predictions, skipped {skipped} (no live data yet)")


def wait_for_todays_feature_rows(symbols, max_minutes=10):
    """Polls until the LAST basket symbol (backfill-all works through them in order) has a feature row dated today
    (IST) - i.e. the refresh the client stopped waiting for has actually finished. True if it did."""
    today = (datetime.now(timezone.utc) + timedelta(hours=5, minutes=30)).date().isoformat()
    probe = symbols[-1]
    deadline = time.time() + max_minutes * 60
    while time.time() < deadline:
        try:
            rows = get_json(f"/api/ml/features/{urllib.parse.quote(probe)}")
            if rows and rows[-1]["tradingDate"] >= today:
                return True
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(30)
    return False


def train_multiday_models_per_symbol(symbols):
    # ml_feature_snapshots is never refreshed automatically (deliberately manual - see
    # MlFeatureSnapshotController) - without this, training silently anchors off whatever day
    # someone last happened to backfill, which can be days stale (caught 2026-09-07: a Sunday
    # backfill meant Monday's multi-day predictions were still anchored to the prior Friday).
    # Recomputing here means every run - not just a manually-remembered one - anchors off today.
    print("Refreshing ml_feature_snapshots before training...")
    try:
        # 103-134 s on an idle server, but the 10:10 IST run (first of the day, right after the 10:00 ingestion)
        # exceeded the old 300 s limit on 21, 22 and 23 Oct 2026, failing the whole run red and delaying the day's
        # multi-day predictions by 1-2 hours (the same-day calls were unaffected). 15 minutes is the new limit, and a
        # timeout is no longer fatal: the server keeps refreshing after the client gives up.
        backfill_result = post_json("/api/ml/features/backfill-all?recentDays=90", {}, timeout=900)
        print(f"  refreshed {backfill_result['instrumentsProcessed']} instruments, "
              f"{backfill_result['totalRowsWritten']} rows written")
    except (TimeoutError, urllib.error.URLError, ConnectionError) as e:
        print(f"  backfill-all did not answer ({e!r}); the server keeps working on it - waiting for today's rows...")
        if not wait_for_todays_feature_rows(symbols):
            print("  today's feature rows are still not there - skipping multi-day predictions this run "
                  "(the next hourly run retries); same-day predictions above are unaffected")
            return None

    print("\nDownloading multi-day feature data...")
    all_rows = []
    for sym in symbols:
        rows = get_json(f"/api/ml/features/{urllib.parse.quote(sym)}")
        all_rows.extend(rows)
    df = pd.DataFrame(all_rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    df = df.sort_values(["instrument", "tradingDate"])
    flagged = {i: list(g.loc[pd.to_numeric(g["dailyReturnPct"], errors="coerce").abs() > CORP_ACTION_GAP_PCT, "tradingDate"])
               for i, g in df.groupby("instrument")}
    df, dropped = drop_corporate_action_windows(df, {i: d for i, d in flagged.items() if d})
    if dropped:
        print(f"  dropped {dropped} daily rows within {CORP_ACTION_WINDOW_DAYS} days of an unadjusted corporate action (demerger)")
    # See MULTIDAY_FEATURES' comment - reused for both training (below) and live prediction
    # (run_multiday_predictions reads the same enriched df), so this only needs computing once.
    df["bigMoveVolumeInteraction"] = pd.to_numeric(df["dailyReturnPct"], errors="coerce") * \
        pd.to_numeric(df["volumeRatio20d"], errors="coerce")

    # On-Balance Volume momentum - backtested 2026-09-29 (obv_backtest.py): real, survives
    # Bonferroni across 3 horizons, but the SIGN is the opposite of OBV's classical bullish
    # reading - strong recent OBV momentum (heavy accumulated buying pressure) predicted WORSE
    # forward returns here (20d: Spearman -0.034, t=-5.05, p<0.0001), not better. A contrarian/
    # exhaustion signal on this basket, not a continuation one.
    def obv_momentum(g):
        vol = pd.to_numeric(g["volume"], errors="coerce")
        ret = pd.to_numeric(g["dailyReturnPct"], errors="coerce")
        signed_vol = np.where(ret > 0, vol, np.where(ret < 0, -vol, 0))
        obv = pd.Series(signed_vol, index=g.index).cumsum()
        return obv.pct_change(10).replace([np.inf, -np.inf], np.nan)

    df["obvMomentum10d"] = df.groupby("instrument", group_keys=False).apply(obv_momentum)

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
        features, gated = select_multiday_features(sub)
        if h == HORIZONS[0] and gated:
            print("  multi-day: held out until they have "
                  f"{MIN_HISTORY_DAYS} days of history (still recorded): " + ", ".join(f"{f} ({d}d)" for f, d in gated.items()))
        model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03,
                               subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
        model.fit(sub[features], sub[target])
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
        feature_row = latest[MULTIDAY_FEATURES].to_frame().T.apply(pd.to_numeric, errors="coerce")  # sliced per model below

        for h in HORIZONS:
            predicted_return = float(models[h].predict(feature_row[models[h].booster_.feature_name()])[0])
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
    print("Fetching the quarterly-results calendar...")
    results_calendar = fetch_results_dates(symbols)
    push_results_calendar(results_calendar)
    intraday_models, latest_day_end_rank, conformal, swing = train_intraday_model(results_calendar)
    run_intraday_predictions(intraday_models, symbols, latest_day_end_rank, results_calendar, conformal, swing)

    # Multi-day: only needs one real run per day - its features/target don't change intraday.
    if already_ran_multiday_today():
        print("\nMulti-day models already ran today - skipping (today's intraday refresh above still happened).")
    else:
        multiday = train_multiday_models_per_symbol(symbols)
        if multiday is not None:
            df, multiday_models, baselines = multiday
            run_multiday_predictions(df, multiday_models, baselines, symbols)

    print("\nDone. Evaluate pending predictions later via POST /api/ai-predictions/evaluate-all")
    print("(already wired into the hourly job automatically).")
