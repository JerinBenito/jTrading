# Trading Signal System — Session Summary (2026-08-30 to 2026-08-31)

A compiled record of everything built and found this session, organized by what actually happened rather than chronological order. See `HANDOFF.md` for the day-by-day operational log and `ml-training/` for all the analysis scripts referenced below.

## Phase B — Historical-analog daily forecast (concluded, negative)

Tested whether matching today's intraday shape (return-so-far, volatility-so-far, RSI, EMA trend) against 2 years of historical days via k-NN could beat the existing plain volatility-scaled re-anchor for same-day close prediction.

- **Simple version** (return-so-far only): consistently worse than baseline at every hour, both instruments.
- **Rich version** (4 features, properly normalized): measurably better than the simple version, but still lost to the plain baseline at 12 of 14 hour-buckets tested.
- **Conclusion**: no exploitable intraday pattern from same-day shape. Kept live as documented endpoints (`/api/forecast/{instrument}/historical-analog-backtest`, `/rich-historical-analog-backtest`), not wired into the live prediction.

## Phase C — Multi-month momentum (concluded, negative)

- First pass (NIFTY/BANKNIFTY alone): underpowered — only ~12 non-overlapping 2-month windows in 2 years.
- Pooled across the NIFTY 50 basket (49 stocks backfilled) for 614 non-overlapping-equivalent samples: correlation **-0.0304** (essentially zero). No momentum edge, confirmed with real statistical power this time.

## Phase D — Live monitoring and per-stock intraday prediction

- **Basket monitoring**: `GET /api/monitor/basket` — live price, EMA/RSI trend, and today's predicted-close deviation for NIFTY, BANKNIFTY, and 49 basket stocks. Mobile "Monitor" tab, sortable by biggest move vs. call.
- **Per-stock intraday prediction**: generalized the same validated model (random walk + gated bias correction + self-calibrating range) from NIFTY/BANKNIFTY-only to all 50 basket stocks. Tap any stock in the app to see its live predicted close/high/low, trajectory chart, and the same walk-forward Analysis section (model choice, bias correction, range calibration, re-anchoring) already shown for the indices — verified against real SBILIFE data (481 samples, same qualitative findings as the indices).
- Deliberately did **not** extend pattern-signal win-rate confidence to the basket — `pattern_stats` is keyed only by pattern id, not instrument, so doing so would corrupt NIFTY/BANKNIFTY's own stats. Flagged as a real schema gap, not fixed.

## ML groundwork — `ml_feature_snapshots` dataset

Built specifically so a future ML phase has 2+ years of labeled data ready, without claiming any live predictive power. One row per (instrument, trading day): OHLCV, daily return, gap, intraday range, EMA9/21 spread, RSI14, ATR14, candle shape (body/wick %), volume ratio, lookback returns (5/10/20/40-day), and **forward-looking** return labels at the same horizons (correctly null until that many days have actually passed — no lookahead). 23,344 rows backfilled across 51 instruments.

## Six hypotheses tested for a real predictive edge — all negative

Every test below used real walk-forward validation (never a single train/test split trusted without a multi-fold check) against real historical data.

| # | Method | Result |
|---|---|---|
| 1 | Intraday k-NN, return-only (Phase B "simple") | Loses to baseline |
| 2 | Intraday k-NN, indicator-rich (Phase B "rich") | Loses to baseline at 12/14 hours |
| 3 | Intraday k-NN, candlestick shape (body/wick + streak) | Loses to baseline, all 4 instruments tested |
| 4 | Real trained ML (LightGBM), intraday, 133K rows, proper holdout | Ties baseline (0.4522% vs 0.4490% error), 51.6% direction accuracy (coin flip) |
| 5 | Multi-day forward returns (5/10/20/40d), absolute framing | First single-split test looked promising at 20/40d — **did not survive** 4-fold walk-forward re-check (1/4 folds won, correlation flips sign fold to fold) |
| 6 | Multi-day forward returns, cross-sectional relative-strength framing | Correlation ~0 (slightly negative at 3/4 horizons), 0-1/4 folds won |

**Volume features** (real trade volume, previously ingested but unused): added to both models. Ranked #1 feature importance by the intraday model, yet didn't move accuracy at all. Made multi-day results slightly worse.

**Options/PCR features**: checked feasibility — only ~2 weeks of history exists (vs. 2+ years of OHLCV), far too little to test rigorously yet. Deferred, not abandoned.

## Pairs trading / cointegration — tested and rejected (the cleanest negative of the session)

Screened all 1,275 possible pairs among the 51 instruments for cointegration, first run looked spectacular (42/68 candidates showed >55% out-of-sample spread reversion) — but ASIANPAINT dominated ~30 of them across economically implausible pairings, traced to its own real, story-specific trend (Grasim/UltraTech's 2024-2026 entry into paints, not genuine co-movement). Swapping which time period does discovery vs. validation and re-running from scratch produced a **completely different winners list with zero overlap** — textbook multiple-testing trap. The two independent runs didn't even agree with each other, let alone beat a baseline.

## Live AI prediction ledger — infrastructure, not yet a validated model

Built because the offline backtest verdict alone wasn't enough — the AI model should be held to the same live, ongoing standard the deterministic model already has (record every prediction, evaluate against the real outcome, judge by rolling accuracy over many predictions, never any single one).

- New `ai_predictions` table + `AiPredictionService`: records predictions, evaluates INTRADAY against that day's actual close and FORWARD_5D/10D/20D/40D against the close N trading days later, computes AI error vs. baseline error and direction correctness. `GET /api/ai-predictions/{instrument}/rolling-accuracy` is the metric that actually matters.
- Evaluation wired into the existing hourly job automatically — no manual step.
- `ml-training/run_daily_ai_prediction.py`: trains fresh LightGBM models on all available history each run, generates that day's live predictions (intraday + 4 forward horizons), submits them via API. First run seeded 255 predictions.
- **Explicitly not a claim the model works** — offline validation found no edge. This is the observation infrastructure for whenever a genuinely validated model exists.

## Automation — GitHub Actions, not local scheduling

First attempt used Windows Task Scheduler on the local machine — correctly rejected once flagged that the laptop being off (e.g. at the office) would silently break it. Also considered running it on the Oracle Cloud VM directly, but that box is genuinely tight (only 65Mi free RAM, already using swap, just from running the Java backend) — adding Python/pandas/lightgbm training there risked destabilizing the live production backend.

Landed on **GitHub Actions**: `.github/workflows/daily-ai-prediction.yml` runs on GitHub's own runners (2 CPU/7GB RAM, fully isolated from production) every weekday at 10:05 IST — shortly after the first real market data of the day lands. Talks only to the public backend API over HTTPS, no secrets needed, sparse-checkout scoped to avoid the repo's other large unrelated projects.

## What's next (tomorrow)

Build a stacking/ensemble meta-model: feed the AI the **raw outputs** of every method above (not a hint about which is best), and let its own training process — genuine trial and error, gradient boosting adjusting and correcting each round — decide how to weight and combine them into one final price. Layer in continuous learning from the live prediction ledger's growing real track record, not just a one-time fit on static historical data.

## Key files

- Backend ML/AI code: `backend/src/main/java/com/jerin/trading/ml/`
- Offline training/analysis scripts: `ml-training/` (`.gitignore`d `data/` and `venv/` — re-download/reinstall as needed)
- Live daily automation: `.github/workflows/daily-ai-prediction.yml`
- Day-by-day operational log: `HANDOFF.md`
- Full reasoning/context memory: `project_trading_signal_longterm_vision.md` (Claude's persistent memory, not in this repo)
