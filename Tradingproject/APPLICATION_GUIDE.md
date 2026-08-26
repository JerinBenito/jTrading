# Trading Signal System — Full Application Guide

Complete technical reference for the whole system: backend, database, mobile app, and cloud
infrastructure. For a running changelog of what changed and why, see
[HANDOFF.md](HANDOFF.md). For a resume-length summary, see
[PROJECT_SUMMARY.md](PROJECT_SUMMARY.md). This document explains how everything actually works,
piece by piece.

---

## 1. What this is

A personal, **deterministic** (no AI/LLM anywhere in the pipeline) trading-signal and
price-forecasting system for two NSE (Indian stock exchange) index instruments — **NIFTY** and
**BANKNIFTY**. It runs unattended in the cloud, pulls real market data every trading hour, and
produces two independent kinds of output:

1. **Pattern-based directional signals** — "this specific, hand-defined chart pattern just
   fired, and historically it's been right N% of the time across M real past occurrences."
2. **Price forecasts** — next hour's close, and today's close, each as a range with a
   confidence band, self-correcting from real outcomes over time.

**Hard constraints that shape every design decision:**
- No AI/LLM call anywhere in the runtime pipeline — every number is deterministic, reproducible,
  and traceable to stored data + code. Claude is a development tool only, never a production
  dependency.
- Never output a bare exact price — always direction + range + a confidence tier backed by a
  real sample size.
- No lookahead in any backtest — an "as of" statistic only ever uses data strictly before that
  point in time.
- Every prediction is persisted and later checked against what actually happened — nothing is
  shown without being logged for evaluation.
- The system never places trades or tells the owner to buy/sell. All trading decisions are
  manual.

---

## 2. System architecture

```
                       ┌─────────────────────────┐
   Upstox (broker) ───▶│   Spring Boot backend    │◀─── Expo/React Native app
   (candles + option   │  (Java 21, single app)   │      (installed on phone,
    chain, OAuth)       │                          │       standalone APK)
                       │  Quartz job, hourly       │
                       │  9am-3pm IST weekdays     │
                       └───────────┬──────────────┘
                                   │
                          ┌────────▼─────────┐
                          │   PostgreSQL 16    │
                          │ (private VM, no     │
                          │  public IP)          │
                          └───────────────────┘
```

**Two Oracle Cloud "Always Free" VMs** (`ap-hyderabad-1` region, genuinely $0/month):
- `trading-signal-db` (private IP only) — PostgreSQL 16
- `trading-signal-app` (public IP `140.245.255.99`) — the Spring Boot backend, behind Caddy
  (automatic HTTPS via Let's Encrypt), reachable at `https://jerintradingsignal.duckdns.org`
  (free DuckDNS domain)

**Backend**: Spring Boot 4.1, Java 21, Maven (wrapper only, no local install needed), Quartz
Scheduler for the hourly job, Flyway for schema migrations, Spring Data JPA, `RestClient` for
the Upstox HTTP calls.

**Mobile app**: Expo SDK 57 / React Native, file-based routing via `expo-router`, TypeScript,
built as a standalone installable Android APK via EAS Build (Expo's cloud build service) — no
Play Store needed, no local Android Studio needed either.

---

## 3. Backend — package by package

All code lives under `backend/src/main/java/com/jerin/trading/`.

### 3.1 `broker` — talking to Upstox

- `BrokerClient` (interface) — broker-agnostic contract: `getCandles(...)` (historical,
  completed days only), `getIntradayCandles(...)` (today's forming candles), `getOptionChain(...)`.
- `broker.upstox.UpstoxBrokerClient` — the only implementation. Calls Upstox's V3 REST API
  directly via `RestClient` (not injected — `RestClient.Builder` isn't autoconfigured in this
  Spring Boot version, so it's built directly in the constructor).
- `UpstoxAuthService` / `UpstoxAuthController` — the OAuth2 authorization-code flow.
  `GET /auth/upstox/login-url` returns the URL to open; Upstox redirects back to
  `GET /auth/upstox/callback?code=...` after login, which exchanges the code for an access
  token.
- `UpstoxTokenStore` — holds the current access token **in memory only**. Lost on every backend
  restart, and Upstox tokens expire daily regardless — there's no way to fully automate this;
  logging in each trading morning is the one manual step in the whole system.
- `broker.upstox.dto` — Upstox's raw JSON response shapes, kept isolated here so the rest of the
  app only ever sees the broker-neutral `Candle` / `OptionChainEntry` records.

### 3.2 `ingestion` — pulling and storing market data

- `Instrument` (enum) — `NIFTY` and `BANKNIFTY` only, mapped to their Upstox instrument keys.
  Each carries its own `optionChainExpiry()` — NIFTY uses `current_week`, BANKNIFTY uses
  `current_month` (NSE discontinued weekly BankNifty options, so `current_week` returned zero
  rows for it until this per-instrument fix).
- `IngestionService` — `ingestCandles()` (historical backfill, auto-chunks any date range into
  ≤89-day windows since Upstox's hourly-candle endpoint caps requests at ~90 days),
  `ingestIntradayCandles()` (today's live data — the one the scheduled job actually calls),
  `ingestOptionChain()`. Both candle paths share a `saveNewCandles()` helper that dedupes on
  `(instrument, interval, ts)` before insert.
- `IngestionJob` — the Quartz job. Not a Spring bean (Quartz instantiates it directly); its
  service dependencies are injected via `JobDataMap`. Per instrument, every run does, in order:
  ingest intraday candles → ingest option chain → generate pattern signals → evaluate pending
  signal outcomes → evaluate pending hourly forecast → record next hourly forecast → evaluate
  pending daily forecast → record today's daily forecast (if not already recorded). Every step
  is individually idempotent, so running the same cycle twice in a row is always safe.
- `QuartzConfig` — two triggers bound to the same job: `ingestionJobTrigger` (hourly, on the
  hour, 9am–3pm IST weekdays) and `endOfDayCatchUpTrigger` (one extra run at 15:45 IST, added
  because the 15:00 run alone always misses the day's final ~15:15 candle).
- `IngestionController` — manual trigger endpoints for testing (`/backfill`, `/intraday`,
  `/option-chain`).

### 3.3 `indicator` — the technical-analysis primitives

All pure, stateless functions operating on ordered candle lists — no I/O, fully unit-tested.

- `EmaCalculator` — exponential moving average. First `period-1` entries are `null` (not
  fabricated).
- `RsiCalculator` — RSI using Wilder's smoothing method.
- `AtrCalculator` — Average True Range, also Wilder's smoothing. This single number underpins
  every forecast range in the system.
- `PcrCalculator` — put-call ratio from a batch of option chain rows (sums OI by CE/PE).
- `IndicatorService` / `IndicatorController` — computes EMA9, EMA21, RSI14, ATR14, and PCR for
  an instrument from stored data, exposed read-only at `GET /api/indicators/{instrument}`.

### 3.4 `pattern` — the 4 hand-defined trading patterns

Deliberately **hand-defined from established technical-analysis logic**, not discovered by
brute-force search over the data (which would overfit).

- `Pattern` (interface) + 4 `@Component` implementations: `EmaBullishCrossPattern`,
  `EmaBearishCrossPattern`, `RsiOversoldReversalPattern` (RSI < 30),
  `RsiOverboughtReversalPattern` (RSI > 70).
- `PatternStatsService` — scans the *full* backfilled history, finds every historical
  occurrence of each pattern, checks the outcome 5 bars later (`HOLDING_PERIOD_BARS`), and
  upserts one `pattern_stats` row per pattern with the real sample size and win rate.
- `PatternController` — `GET /api/patterns/stats` (read), `POST /api/patterns/{instrument}/recompute`.

### 3.5 `signal` — turning a firing pattern into a logged prediction

- `SignalService.generateSignals()` — checks each pattern against only the *latest* bar. Only
  emits a signal if `pattern_stats` already has a real sample for that pattern (never fabricates
  a confidence tier). Confidence tier: sample size < 30 → `low` regardless of win rate; win rate
  ≥ 65% → `high`; ≥ 55% → `medium`; else `low`. Expected range is always `close ± ATR14`, never
  a bare price. Has a dedup guard (`existsByInstrumentAndPatternIdAndTs`) so a manual test call
  overlapping a scheduled run can't double-log.
- `OutcomeEvaluationService.evaluatePending()` — finds predictions where 5 bars have elapsed
  since, computes the actual direction/move, and persists the outcome.
- `SignalController` — `GET /api/signals/{instrument}` (live check), `GET /api/signals/{instrument}/history`,
  `POST /api/signals/{instrument}/evaluate-outcomes`.

### 3.6 `forecast` — the price-prediction system (the largest, most-developed package)

This is really **four sub-systems**, each validated by its own walk-forward backtest before
ever going live, sharing common building blocks:

#### a) Hourly forecast — `ForecastPredictionService`
Runs every scheduled cycle. Predicts next hour's close using `RandomWalkForecastModel`
(`predict = current close`, empirically beat `MomentumForecastModel` — a damped EMA9-EMA21
drift — in the Phase 1 backtest, on both instruments) plus a **significance-gated bias
correction** and a **self-calibrating range width** (both explained in §4). Model name in the
DB: `RANDOM_WALK_GATED_BIAS_CORRECTION`.

#### b) Same-day close forecast — `DailyForecastPredictionService`
Records one prediction per trading day at market open (`DailyRandomWalkModel`: predict = today's
open, range = yesterday's daily ATR14 — beat `DailyMomentumModel` the same way the hourly base
model did), evaluates it once the day is over. Reuses the *same* `hourly_predictions` table as
the hourly loop, just with `interval="1d"` — no separate table needed, since the row shape is
identical. Idempotent and self-healing (checks whether today already has a row before creating
one). Model name: `DAILY_RANDOM_WALK_GATED_BIAS_CORRECTION`.

`DailyBarAggregator` builds the daily bars this whole sub-system needs by aggregating the
already-ingested hourly candles into one bar per IST calendar day — no separate broker call.

#### c) Trajectory tracking — `DailyTrajectoryService`
Pure reporting layer, nothing persisted: for a given day, shows each hour's actual price
against the morning's prediction (deviation, deviation %, in-range or not). Also computes a
**live re-anchored estimate** (§4d) on every read.

#### d) Intraday re-anchoring — `IntradayReanchorCalculator` / `IntradayReanchorBacktestService`
The mechanism behind the trajectory's live estimate — see §4d for the full explanation.

**Backtest tooling** (all read-only, recompute fresh from stored history on every call, nothing
cached): `ForecastBacktestService`, `BiasCorrectionBacktestService`,
`RangeCalibrationBacktestService`, `DailyForecastBacktestService`,
`DailyBiasCorrectionBacktestService`, `DailyRangeCalibrationBacktestService`,
`IntradayReanchorBacktestService` — one comparison service per question asked of the data, each
built and run *before* the corresponding live mechanism was trusted.

### 3.7 `domain` and `repository`

JPA entities and Spring Data repositories for every table (§5). Nothing unusual — standard
Spring Data JPA, composite-key entities (`PatternStatsId`) where the table has a composite
primary key.

---

## 4. The four statistical mechanisms — how the "trial and error" actually works

Every one of these follows the same discipline: **diagnose a real problem from real data →
design a fix grounded in an established statistical/mathematical technique → backtest it
walk-forward against 2 years of real history with no lookahead → only then deploy it live.**

### a) Significance-gated bias correction (`BiasCorrectionCalculator`)
**Problem it replaced**: a naive rolling correction (flat average of the last 20 signed errors,
added unconditionally to the next prediction) was found to make predictions *worse*, not
better — confirmed both in live production data and in a full 2-year backtest.

**Why**: at this granularity, prices are close to a random walk, so a small-sample average of
near-zero-mean noise is itself just noise. Applying it as if it were signal adds error.

**The fix**: treat the rolling error window as a statistical test. Compute the mean and standard
error of the last N errors; only apply a correction if the mean exceeds 1.5× its own standard
error (the same logic as a one-sample t-test — is this distinguishable from noise at all?), and
even then only lean into 50% of the estimate (shrinkage), never the full noisy point estimate.
`MIN_WINDOW = 30` observations required before it ever activates.

**Result** (backtested against 2 years): the naive approach really was worse than doing nothing
on both instruments and both timeframes; the gated version lands within a hair of the
uncorrected baseline — correctly recognizing that real, non-noise bias is rare at this
granularity.

### b) Online-adaptive range calibration (`RangeCalibrator`)
**Problem it replaced**: the prediction *range* (the ± band) was always a fixed `1× ATR`,
completely static, never checked against how often the actual price really landed inside it.

**The fix**: an online, incremental update (the same family of technique as Adaptive Conformal
Inference / online quantile tracking). After every evaluated prediction, nudge a persisted
multiplier on ATR — up if the actual value fell outside the range, down more gently if it fell
inside — sized so the long-run hit rate converges toward a 90% target:

```
miss = 1 if not covered else 0
next_multiplier = current_multiplier + learning_rate × (miss − (1 − target_coverage))
```

One multiplier per instrument+interval (4 total: NIFTY/BANKNIFTY × hourly/daily), persisted in
the tiny `range_calibration_state` table, clipped to `[0.5, 3.0]` to prevent runaway drift.

**Result**: the hourly range was quietly under-covering (~86% actual coverage against a 90%
target) — the adaptive multiplier converged to ~1.2–1.3× and fixed it, at the honest cost of
~20% wider hourly ranges. The daily range was already close to well-calibrated, so the
calibrator correctly left it mostly alone (~0.9–1.0×).

### c) Model selection (random walk vs. momentum)
Both the hourly and daily forecasts compare a plain random-walk baseline against a
momentum/drift variant (a damped fraction of the EMA9-EMA21 spread) via walk-forward backtest.
Random walk won on every metric, on both instruments, at both timeframes — a genuine,
empirically-justified finding (short-horizon financial prices are close to a random walk, a
well-documented phenomenon), not an assumption.

### d) Intraday re-anchoring (`IntradayReanchorCalculator`)
**Problem it addresses**: the same-day close prediction is calculated once at market open and
never touched again all day, even if the price action strongly diverges from what was expected
by mid-morning.

**Grounded in**: the same idea used in the forecasting literature for "day-ahead → intraday"
updates (sometimes called a "living forecast") — condition the remaining-horizon estimate on
what's now actually known, and shrink the remaining uncertainty as less trading time is left.
The uncertainty-shrinkage law is classic stochastic calculus: variance grows linearly with time,
so the range should scale with the **square root** of the remaining session fraction (the same
law behind Black-Scholes volatility scaling):

```
remaining_fraction = 1 − (elapsed_minutes / 375)   # 375 = NSE's nominal 09:15–15:30 session
range_width = daily_ATR × √(max(remaining_fraction, 0.05))   # floor prevents collapse to zero
estimated_close = latest known price   # re-anchored random walk
```

**Backtested specifically against the scenario that motivated it** — "what if the price already
broke outside the expected range by ~10:15 IST": on those flagged days, the static prediction's
coverage of the actual final close collapsed to **25% (NIFTY) / 38% (BANKNIFTY)** — i.e. it was
more often wrong than right. Re-anchoring to the new information on those same days brought
coverage back up to **100% / 92%**, with roughly 4× lower error. Across all days (not just the
flagged ones), error shrinks steadily through the session as more real information accumulates.

**Deployed as**: a live, read-only computation inside `DailyTrajectoryService` — no new table,
recomputed on every read from the day's candles-so-far plus yesterday's ATR. Surfaced in the
mobile app as the "Live re-anchored estimate" card on the trajectory chart.

---

## 5. Database schema

Single PostgreSQL instance, all tables in the default schema, migrations managed by Flyway
(`backend/src/main/resources/db/migration/`).

| Table | Purpose | Key columns |
|---|---|---|
| `ohlcv_candles` (V1) | Every candle ever ingested, both historical and intraday, both hourly (`interval="1h"`) and daily-aggregated views computed on the fly (not stored) | `instrument, interval, ts` (unique), `open/high/low/close`, `volume` |
| `option_chain_snapshot` (V1) | Option chain rows pulled alongside candles | `instrument, expiry, strike, option_type, ts`, `oi/change_oi/iv/ltp/volume` |
| `signal_predictions` (V1) | Every pattern-based signal ever emitted | `instrument, ts, pattern_id`, `inputs_snapshot` (JSONB), `predicted_direction, confidence_tier, sample_size_at_time` |
| `signal_outcomes` (V1) | Resolved outcome for each signal prediction | `prediction_id` (FK, unique), `actual_direction, actual_move_pct, evaluated_at` |
| `pattern_stats` (V1) | Rolling win-rate per pattern | `pattern_id, window_end` (composite PK), `sample_size, win_rate, avg_move_pct` |
| `hourly_predictions` (V2) | **Shared by both the hourly forecast AND the same-day close forecast** — distinguished only by `interval` (`"1h"` vs `"1d"`) | `instrument, interval, predicted_for_ts` (unique together), `model_name, predicted_close, range_low/high, bias_correction_applied, actual_close, error_pct, evaluated_at` |
| `range_calibration_state` (V3) | Current online-calibrated ATR multiplier, one row per instrument+interval (4 rows total) | `instrument, interval` (unique together), `multiplier, updated_at` |

Deliberately **no separate tables for "current" vs "historical" data anywhere** — every table is
a single canonical time series, per the original spec's hard constraint.

---

## 6. Full API reference

Base URL: `https://jerintradingsignal.duckdns.org`. `{instrument}` is `NIFTY` or `BANKNIFTY`.
`POST` endpoints need a tool like `curl`, not just a browser visit.

**Health & auth**
- `GET /actuator/health`
- `GET /auth/upstox/login-url` — returns `{loginUrl}` to open (or embed in a WebView)
- `GET /auth/upstox/callback?code=...` — fires automatically after Upstox login

**Indicators**
- `GET /api/indicators/{instrument}?interval=1h`

**Ingestion** (manual triggers — the scheduled job calls these automatically)
- `POST /api/ingestion/{instrument}/backfill?from=YYYY-MM-DD`
- `POST /api/ingestion/{instrument}/intraday`
- `POST /api/ingestion/{instrument}/option-chain`

**Patterns**
- `GET /api/patterns/stats`
- `POST /api/patterns/{instrument}/recompute`

**Signals**
- `GET /api/signals/{instrument}` — live check (manual trigger)
- `GET /api/signals/{instrument}/history`
- `POST /api/signals/{instrument}/evaluate-outcomes`

**Forecast — hourly & same-day close (shared endpoints, `interval` query param selects which)**
- `GET /api/forecast/{instrument}/history?interval=1h|1d`
- `POST /api/forecast/{instrument}/predict-next?interval=1h` (hourly manual trigger)
- `POST /api/forecast/{instrument}/evaluate?interval=1h`
- `POST /api/forecast/{instrument}/daily/predict-today` (daily manual trigger)
- `POST /api/forecast/{instrument}/daily/evaluate`
- `GET /api/forecast/{instrument}/daily/trajectory?date=YYYY-MM-DD` (defaults to today, IST;
  includes the live re-anchored estimate fields)
- `GET /api/forecast/{instrument}/range-calibration?interval=1h|1d`

**Forecast — backtests / analysis** (all read-only, recomputed fresh every call)
- `GET /api/forecast/{instrument}/backtest?interval=1h` — random walk vs. momentum, hourly
- `GET /api/forecast/{instrument}/daily-backtest` — random walk vs. momentum, daily
- `GET /api/forecast/{instrument}/bias-correction-backtest?interval=1h` — none vs. naive vs. gated
- `GET /api/forecast/{instrument}/daily-bias-correction-backtest`
- `GET /api/forecast/{instrument}/range-calibration-backtest?interval=1h` — fixed vs. adaptive
- `GET /api/forecast/{instrument}/daily-range-calibration-backtest`
- `GET /api/forecast/{instrument}/intraday-reanchor-backtest` — static vs. re-anchored, by hour,
  plus the "big morning miss" comparison

---

## 7. Mobile app

Expo/React Native, TypeScript, `expo-router` file-based routing, dark theme throughout. Talks
only to the REST API above — no local business logic beyond formatting/display.

**Structure**:
```
frontend/
  app/
    _layout.tsx          root layout (SafeAreaProvider, InstrumentProvider, Stack)
    (tabs)/
      _layout.tsx         bottom tab navigator
      index.tsx           Dashboard
      history.tsx          History
      signals.tsx          Signals
      analysis.tsx          Analysis
      login.tsx            Login
  src/
    api/                  client.ts (typed fetch wrapper), types.ts
    components/           all shared UI pieces
    context/              InstrumentContext (NIFTY/BANKNIFTY selection, shared across tabs)
    hooks/                useApiData (loading/error/refresh pattern used everywhere)
    constants/             config.ts (API base URL), colors.ts (theme)
    utils/                 groupByDay.ts, smoothPath.ts (chart curve math)
```

**5 tabs**:
1. **Dashboard** — instrument toggle, system health badge, today's close prediction card,
   the trajectory chart (with the live re-anchored estimate overlay), latest hourly forecast
   card, range-calibration multiplier row.
2. **History** — full hourly and same-day-close prediction history, grouped by day
   (Today/Yesterday/dates), each row showing predicted vs. actual, error %, bias correction
   applied, covered/missed status.
3. **Signals** — pattern signal history with outcomes.
4. **Analysis** — all the backtest comparisons from §6, live: model choice, bias correction,
   range calibration (both hourly and daily), plus the intraday re-anchoring table and the
   "big morning miss" callout.
5. **Login** — the daily Upstox re-auth, embedded via `react-native-webview` instead of an
   external browser tab; detects the OAuth callback redirect and shows a native confirmation.

**Custom trajectory chart** — built directly on `react-native-svg` (no charting library
dependency, after an attempt to use one hit an unverifiable prop surface): smooth Catmull-Rom
curve, gradient area fill, price/time axis labels, a shaded band for the morning's predicted
range, a second narrower accent-colored band for the live re-anchored range, and a legend.

**Distribution**: standalone installable Android APK, built via EAS Build (Expo's cloud build
service) under the `preview` profile (`eas.json`) — no Play Store, no local Android SDK needed.
Rebuild command: `cd frontend && EAS_NO_VCS=1 npx eas-cli build --platform android --profile preview --non-interactive`
(the `EAS_NO_VCS=1` and a root-level `.easignore` are required because the git repo root sits
above this project, alongside unrelated large sibling folders — without them EAS tries to
archive the whole monorepo).

---

## 8. Testing

60 backend unit tests (all pure logic — calculators, models, patterns, aggregators — no Spring
context needed for most of them), all passing. The one `SignalBackendApplicationTests` context-
load test fails locally by design (needs a local Postgres that stopped running once the system
moved to the cloud) — expected, not a regression, and irrelevant to correctness since the real
system is verified live against the cloud database instead.

Frontend has no automated test suite (a personal-use app driven by manual device testing plus
TypeScript's compiler and `expo export`'s full bundle check catching structural errors before
every build).

---

## 9. Operational runbook

**Daily routine**:
1. Open the app's Login tab (or `https://jerintradingsignal.duckdns.org/auth/upstox/login-url`
   in a browser) and log into Upstox — ~30 seconds, tokens expire daily, no way to automate this.
2. Everything else is automatic: the hourly job ingests data, checks patterns, evaluates and
   records both forecasts, all self-healing and idempotent.
3. Check the app any time — Dashboard for the current state, History for the full record,
   Analysis to see whether the self-correcting mechanisms are actually earning their keep.

**Backend redeploy** (after any code change):
```bash
cd backend && ./mvnw clean package -DskipTests
```
Upload `target/signal-backend-0.0.1-SNAPSHOT.jar` to Oracle Cloud Shell (☰/⋮ → Upload), then:
```bash
scp -i ~/.ssh/trading_vm_key signal-backend-0.0.1-SNAPSHOT.jar opc@140.245.255.99:~/
ssh -i ~/.ssh/trading_vm_key opc@140.245.255.99 "sudo systemctl restart trading-signal"
```
Wait ~20-40s, then confirm `GET /actuator/health` returns `{"status":"UP"}`. Any new Flyway
migration in `db/migration/` applies automatically on startup.

**Frontend rebuild**:
```bash
cd frontend && EAS_NO_VCS=1 npx eas-cli build --platform android --profile preview --non-interactive
```
Produces a downloadable, directly-installable APK link (no Play Store).

---

## 10. Infrastructure notes worth remembering

- Both VMs are `VM.Standard.E2.1.Micro` (1 OCPU / 1GB RAM), within Oracle's Always Free
  allowance — genuinely $0/month, confirmed against Oracle's own docs.
- 1GB RAM needs swap space added before any package installs, or `dnf`/builds get silently
  OOM-killed.
- The DB VM has no public IP; reach it via SSH ProxyJump through the app VM
  (`ssh -J opc@140.245.255.99 opc@10.0.0.226`), needs `ssh-agent`/`ssh-add` first.
- Oracle Cloud Shell sessions reset periodically and silently clear bash variables — always
  `echo $VAR` before reusing one after a gap.
- HTTPS via Caddy (automatic Let's Encrypt) + DuckDNS free domain — Upstox requires HTTPS
  redirect URIs, a bare IP can't get a Let's Encrypt cert.

---

## 11. What's not built (roadmap, not started)

- Notifications (Telegram/email) — would remove the need to open the app to check in
- PCR-based pattern (no historical option-chain data source exists yet to backtest one against)
- Full Phase-4 backtest engine for the *pattern* system specifically (transaction costs,
  drawdown, walk-forward validation) — the forecast loops already do continuous recalibration,
  but the pattern win-rates don't yet
- Arbitrary-stock long-horizon (2-3 month) range prediction — flagged as its own separate
  initiative: needs volatility-scaled range math (not per-hour/per-day random walk), new
  instrument lookup/backfill, and a different on-demand (not scheduled) interaction pattern.
  Not started, needs its own scoping discussion before beginning.
- Sensex, broader equities, commodities (MCX) — would need separate data sources entirely,
  discussed but out of scope for now.
