# Trading Signal System — Status as of 2026-08-25

## What this is
A personal, deterministic (no AI/LLM in the pipeline) NSE trading-signal system for NIFTY and BANKNIFTY. Pulls market data hourly, checks 4 hand-defined patterns against real historical win rates, and runs a self-correcting rolling one-hour-ahead price forecast. Never recommends trades or gives a false sense of precision — only direction/range + a confidence tier backed by a real sample size. The owner (Jerin) makes all trading decisions manually.

## What prediction does it actually make? (two separate systems, run in parallel)

**1. Pattern-based signals** — checks the latest hour against 4 specific hand-defined conditions (EMA9/21 crossovers, RSI oversold/overbought). Only logs something when one of these conditions actually occurs — most hours, nothing fires, which is normal, not a failure. When one does fire, it logs direction (up/down) + a confidence tier backed by a real historical win rate (73-385 past occurrences per pattern) + an expected range.

**2. Rolling hourly forecast** — runs every hour regardless of conditions. Predicts next hour's closing price ± a range, using a random-walk baseline ("next price ≈ current price" — tested against a fancier momentum-based alternative and the simple version won on real data) plus a rolling self-correction based on its last 20 prediction errors.

**What it explicitly does NOT do**: predict across the overnight market-closed gap (today's close → tomorrow's open). This was discussed and deliberately deferred — overnight gaps are driven by inputs this system doesn't ingest at all (US markets, SGX Nifty, crude, currency, global news), so a same-close guess there wouldn't be a real edge, just a weaker version of the existing model. Revisit only once global-market data ingestion is ever added — no such ingestion exists today. Both systems also never give a bare exact price, and never tell the owner to buy/sell — direction/range/confidence only, decisions stay manual.

## Current deployment — fully live in the cloud
Runs on **Oracle Cloud Always Free** (region `ap-hyderabad-1`), completely free, two VMs:
- `trading-signal-db` (private IP `10.0.0.226`) — PostgreSQL 16, not internet-facing
- `trading-signal-app` (public IP `140.245.255.99`) — Spring Boot backend, runs as systemd service `trading-signal`

**Public URL (HTTPS, via Caddy + Let's Encrypt + DuckDNS free domain):**
`https://jerintradingsignal.duckdns.org`

## Daily routine
1. **Morning — the one manual step:**
   ```
   https://jerintradingsignal.duckdns.org/auth/upstox/login-url
   ```
   Open it, follow the link, log into Upstox (credentials + TOTP). ~30 seconds. Upstox tokens expire daily — no way around this, no non-interactive auth exists.
2. **Automatic from there**: the hourly job (weekdays 9am-3pm IST) ingests new data, checks all 4 patterns, saves a signal if one fires, evaluates past pattern outcomes, evaluates the last hourly forecast against what actually happened, and records a new forecast for the next hour. No further action needed.
3. **Evening / whenever — check what actually happened:**
   ```
   https://jerintradingsignal.duckdns.org/api/signals/NIFTY/history
   https://jerintradingsignal.duckdns.org/api/signals/BANKNIFTY/history
   https://jerintradingsignal.duckdns.org/api/forecast/NIFTY/history
   https://jerintradingsignal.duckdns.org/api/forecast/BANKNIFTY/history
   ```
   Empty pattern history = normal (patterns don't fire daily). Forecast history should have one new entry per trading hour once things are running.

## All endpoints
- Health: `GET /actuator/health`
- Upstox login: `GET /auth/upstox/login-url` (then `/auth/upstox/callback` fires automatically)
- Live indicators: `GET /api/indicators/{instrument}?interval=1h` (EMA9/21, RSI14, ATR14, PCR)
- Manual backfill (past completed days only): `POST /api/ingestion/{instrument}/backfill?from=YYYY-MM-DD`
- Manual intraday ingest (today's live candles — what the scheduled job actually calls each hour): `POST /api/ingestion/{instrument}/intraday`
- Manual option chain pull: `POST /api/ingestion/{instrument}/option-chain`
- Pattern stats: `GET /api/patterns/stats`
- Manual pattern recompute: `POST /api/patterns/{instrument}/recompute`
- Live signal check (manual trigger): `GET /api/signals/{instrument}`
- Signal history (read-only): `GET /api/signals/{instrument}/history`
- Manual outcome evaluation: `POST /api/signals/{instrument}/evaluate-outcomes`
- Forecast model backtest (compares candidate models vs history): `GET /api/forecast/{instrument}/backtest`
- Manual forecast trigger: `POST /api/forecast/{instrument}/predict-next`
- Manual forecast evaluation: `POST /api/forecast/{instrument}/evaluate`
- Forecast history (read-only): `GET /api/forecast/{instrument}/history`

`{instrument}` is `NIFTY` or `BANKNIFTY`. `POST` endpoints need a tool like curl, not just a browser visit.

## Bug fixed 2026-08-18: live ingestion was silently pulling zero candles
The hourly job was asking Upstox's **historical** candle endpoint for "today" — that endpoint only ever returns *completed* trading days, so it silently returned nothing (no error) every single cycle since Phase 2 deployed. Fixed by adding a separate call to Upstox's **intraday** candle endpoint (same response format, no date params, just returns today's forming candles) for the live job specifically, while backfills of past dates still correctly use the original historical endpoint. Verified live: 7 real candles ingested per instrument on the first test after the fix, indicator values changed meaningfully from stale week-old numbers.

## Confirmed working correctly 2026-08-19: forecast loop ran 5/5 hours, no bugs
A report came in claiming forecast evaluation looked "stuck" (checked at 10:38 IST, no update yet). **Verified directly against the database** rather than trusting the report at face value — it's a false alarm. Evaluation only happens on the *next* scheduled hourly job run (a prediction targeting 10:15 gets checked at 11:00, not instantly at 10:15) — the check was simply too early. Direct DB query confirmed **5 for 5 successful predict+evaluate cycles** that day for NIFTY, every prediction landing within its own range, errors all under 0.14%. The pipeline is genuinely working end to end now.

One minor, non-urgent finding from that investigation: the very last prediction of each day (targeting ~15:15 IST, market close) may never get evaluated, since the last scheduled job (15:00 IST) runs before that candle even forms. Low-impact (one prediction/instrument/day), not fixed yet — parked for the end-of-week review below.

**Today (2026-08-19) is the first day the full pipeline — ingestion → patterns → forecast — has run cleanly on real data for a whole session.** Current plan: let it run untouched (just the daily login) for about a week, then review real accumulated results and decide what's worth improving — see "This week's plan" below.

## This week's plan (agreed 2026-08-18, target review ~2026-08-25)
Let the system run for real, untouched, for about a week — daily login only, no code changes unless something new comes up. At the end of the week, review:
- Forecast accuracy trend via `/api/forecast/{instrument}/history` — is the rolling bias correction reducing error as real data accumulates? (Correction only activates once 20 evaluated predictions exist per instrument — was still at 0 as of 2026-08-19, so this hasn't kicked in yet.)
- Whether any of the 4 patterns fired during the week, and whether outcomes matched historical win rates, via `/api/signals/{instrument}/history`
- Then decide what's actually worth improving based on real evidence, rather than adding complexity speculatively

Explicitly deferred until then (or later): overnight gap prediction (today's close → tomorrow's open) — discussed and intentionally not built, since it would need data sources (global indices, SGX Nifty, currency, crude) that don't exist in the pipeline yet; a same-close guess without that data wouldn't add real value.

## Second false alarm, same pattern, confirmed via DB 2026-08-20: NIFTY forecast falsely reported "stopped"
A carefully cross-checked report (compared NIFTY vs BANKNIFTY by ID sequence, confirmed app health, ruled out a global outage) claimed NIFTY's forecast loop stopped after 11:15 IST while BANKNIFTY kept going — a specific, plausible-sounding, well-evidenced bug report. **Investigated properly rather than assumed correct**: checked logs for a "cycle failed" error (none), checked candle ingestion for both instruments directly in the DB (both fine, identical coverage), then queried the `hourly_predictions` table directly for NIFTY. Result: **a complete, unbroken, correctly-evaluated sequence** — 11:15→12:15 (evaluated 13:00), 12:15→13:15 (evaluated 14:00), 13:15→14:15 (evaluated 15:00), 14:15→15:15 (pending, the known/expected last-candle-of-day case). No gap existed at the time of the direct DB check.

This is now the **second time** a carefully-written bug report (2026-08-19 and 2026-08-20) turned out to contradict direct database evidence. Both times the system was actually working correctly. Likely explanation both times: the checking session was looking at a stale/cached response rather than a genuinely fresh request — not a backend bug. **Established practice going forward**: always verify any "it's broken" report directly against the database or a guaranteed-fresh request (e.g. `curl` from a fresh shell) before trusting it or making code changes — this has caught two false alarms in two days, so it's a real, recurring failure mode of how checks are being done, not of the system itself.

No code changes made that day. System status unchanged from 2026-08-19: working correctly, this week's "let it run" plan still in effect.

## Progress check + a real bug fixed 2026-08-21
Three trading days in since the ingestion fix, checked real accumulated numbers directly via DB:
- **Forecast accuracy**: NIFTY avg error 0.0485%, BANKNIFTY 0.0913% — both better than the original backtest. 15/20 evaluated predictions each (the rolling bias correction activates at 20 — close, not active yet as of this writing).
- **Pattern signals**: BANKNIFTY `EMA_BULLISH_CROSS` 1/1 correct. NIFTY `EMA_BULLISH_CROSS` fired once, still pending. NIFTY `RSI_OVERSOLD_REVERSAL` fired 7 times, 6 correct — small sample, the 2-year backtest rate for this pattern was ~52%, so don't read too much into the early 86% yet.
- **Minor issue spotted, not fixed**: two identical rows in `signal_predictions` for the same pattern+hour on 08-19 — likely a manual test call overlapping a scheduled run. `SignalService.generateSignals` has no duplicate-prevention check (unlike the forecast service, which does). Low priority, left for later.

**Fixed today**: the last-candle-of-day gap (previously noted as minor/deferred) turned out to be measurably slowing down the 20-sample bias-correction threshold — costing ~1 evaluation/day/instrument. Added a second scheduled run at 15:45 IST (`endOfDayCatchUpTrigger` in `QuartzConfig`, same job, safe to run twice since everything dedupes) that catches the ~15:15 candle the regular 15:00 run always misses. Deployed and confirmed healthy — takes effect from the next trading day onward.

Week's plan otherwise unchanged, target review still ~2026-08-25.

## Third false alarm confirmed + resolved 2026-08-24: mobile session's web_fetch is the real culprit
Same pattern a third time — "BANKNIFTY stalled since the 20th" reported, directly verified via DB, and found completely current instead (6 fresh predictions that day, both instruments in lockstep). This is now 3-for-3: every "it's broken" report has contradicted direct DB evidence. Root cause attributed to the mobile session's `web_fetch` tool serving stale/cached responses for these live endpoints — established practice: don't trust `web_fetch` for this system's live data, only trust direct Cloud Shell/DB queries. Bonus: this check also confirmed the Aug 21 end-of-day fix is genuinely working (BANKNIFTY's last prediction of the day evaluated right on schedule at 15:45 IST).

Same day, bias correction activated for real for the first time (both instruments crossed the 20-evaluated-prediction threshold) — worth noting honestly that its first two corrected predictions were *less* accurate than the immediately-preceding uncorrected ones (small sample, not conclusive, worth watching into the Aug 25 review).

## Two more evidence-based fixes shipped 2026-08-24
After reviewing a week of real data, fixed two things — deliberately small, not speculative additions:
1. **BANKNIFTY's PCR had been `null` this entire time** — traced to the original day-1 finding (BankNifty's option chain returns 0 rows for `current_week`, since NSE discontinued weekly BankNifty options) that was noted but never actually fixed. `Instrument` enum now carries a per-instrument `optionChainExpiry()` — BANKNIFTY uses `current_month`. Verified live: 334 real rows pulled (was 0), PCR now populated (0.71, was null).
2. **Duplicate signal rows** (spotted 08-21) — added the same dedup guard `ForecastPredictionService` already had, now also in `SignalService.generateSignals`.

Both deployed, confirmed healthy. Bigger model questions (does bias correction actually help, more patterns, etc.) deliberately left for the Aug 25 review rather than decided now.

## Bias correction fixed with real statistics 2026-08-25
The Aug 24 finding (bias correction's first corrected predictions were less accurate than the uncorrected ones right before it) was investigated properly instead of just watching and waiting, per this week's plan.

**Diagnosis, confirmed against real production data**: the naive correction (flat average of the last 20 signed errors, added unconditionally) treats *any* recent average as real signal — but hourly NIFTY/BANKNIFTY prices are close to a random walk (already established by the Phase 1 backtest, where a momentum model also lost to plain random walk), so a 20-sample average of near-zero-mean noise is itself mostly noise. Applying it unconditionally adds error rather than removing it.

**Fix — `BiasCorrectionCalculator`**: only applies a correction when the observed mean error is statistically distinguishable from noise (mean vs. standard error, the same logic as a one-sample t-test — needs ≥30 samples and the mean must exceed 1.5x the standard error), and even then only leans into 50% of the estimate (shrinkage), never the full noisy point estimate.

**Validated before touching anything live** (`BiasCorrectionBacktestService`, walk-forward, no lookahead, full 2-year stored history — same "backtest before trust" approach as the original model comparison):

| Instrument | No correction | Naive 20-avg (was live) | Gated (now live) |
|---|---|---|---|
| NIFTY | 0.1852% error | 0.1892% (worse) | 0.1858% |
| BANKNIFTY | 0.2159% error | 0.2226% (worse) | 0.2168% |

Confirms the naive approach really was worse than doing nothing, over the full 2-year history — not just a bad week. The gated version neutralizes that harm and lands essentially on par with plain random walk (BANKNIFTY's range coverage is even slightly better: 86.71% vs 86.62%) — correctly recognizing that at hourly granularity there's rarely a real, non-noise bias worth correcting for.

**Deployed**: `ForecastPredictionService.rollingBias()` now calls `BiasCorrectionCalculator` (50-sample window) instead of the flat 20-average. Model name changed to `RANDOM_WALK_GATED_BIAS_CORRECTION` so new predictions are distinguishable from old naive-corrected rows in `hourly_predictions`. 4 new unit tests, all pass. Built, deployed via the standard redeploy procedure, confirmed healthy.

## Same-day open-to-close prediction built and deployed 2026-08-25
Third planned feature from the Aug 25 review, approved to build next. Predicts today's close at market open, evaluated once the trading day is over — same statistical rigor as the bias-correction fix above, not a quick addition.

**Phase 1 (backtest, no live impact)**: `DailyRandomWalkModel` (predicted close = today's open, range = yesterday's ATR14) vs `DailyMomentumModel` (adds a damped fraction of yesterday's EMA9-EMA21 spread), built from daily bars aggregated from the hourly candles already ingested (`DailyBarAggregator` — no separate broker call needed). Walk-forward, no lookahead: only yesterday-and-earlier indicator values are ever used to predict today's close. Exposed at `GET /api/forecast/{instrument}/daily-backtest`.

**Real result against ~487 trading days**: random walk beat momentum on both instruments, mirroring the hourly finding — NIFTY 0.4953% vs 0.5309% mean error, BANKNIFTY 0.5822% vs 0.6232%.

**Bias correction validated the same way** (`DailyBiasCorrectionBacktestService`, `GET /api/forecast/{instrument}/daily-bias-correction-backtest`): naive 20-day flat-average correction made predictions worse on both instruments (NIFTY 0.5045%, BANKNIFTY 0.5978%), the significance-gated correction (same `BiasCorrectionCalculator` as the hourly fix, 60-day window) stayed within a hair of the uncorrected baseline (NIFTY 0.4963%, BANKNIFTY 0.5834%) — same conclusion as hourly: real bias is rare at this granularity too.

**Phase 2 (live, deployed)**: `DailyForecastPredictionService` — records one prediction per trading day at market open (idempotent, self-healing if the first run of the day is late), evaluates it once the IST calendar date has moved past that day (using the last hourly candle of that date as the actual close). Reuses the existing `hourly_predictions` table with `interval="1d"` — no new migration needed, same columns fit exactly. Wired into the existing hourly `IngestionJob`, safe to run every cycle. Manual triggers: `POST /api/forecast/{instrument}/daily/predict-today`, `POST /api/forecast/{instrument}/daily/evaluate`; history via the existing `GET /api/forecast/{instrument}/history?interval=1d`.

**Verified live**: first real predictions recorded same day — NIFTY close 24175.75 (range 23993.74–24357.76), BANKNIFTY close 57360.65 (range 56820.90–57900.40), bias correction 0 on both (correct, no evaluated daily history yet). Will evaluate automatically once today's trading day is over.

## Trajectory tracking built and deployed 2026-08-26
Last of the three planned forecast features from the Aug 25 review — a reporting layer on top of the same-day close prediction, not a new predictive model. Shows how the actual price moved toward or away from the morning's predicted close, hour by hour.

Purely derived from data already stored (no new table, nothing new persisted): `DailyTrajectoryService` looks up the day's `1d` prediction row plus that day's `1h` candles, and computes each hour's deviation from the prediction (absolute and %) plus whether it's still within the predicted range. Endpoint: `GET /api/forecast/{instrument}/daily/trajectory?date=YYYY-MM-DD` (defaults to today, IST).

**Verified live** against 2026-08-25's real data: NIFTY oscillated within its predicted range all session, ending +0.66% above the morning's prediction by the last tracked hour; BANKNIFTY stayed within range too, +0.27% by its last tracked hour. `actualClose` on the response is null until that day's prediction gets evaluated (happens automatically on the next scheduled job run once the day is over) — expected, not a bug.

All three Aug 25-approved forecast features (same-day close prediction, its bias correction, and trajectory tracking) are now live. Arbitrary-stock long-horizon prediction remains deferred as its own separate initiative.

## Self-calibrating range width built and deployed 2026-08-26
Jerin asked to keep improving the hourly and daily forecasts' mathematical rigor — specifically wanted the "trial and error" to genuinely get more precise as more data accumulates, not just apply a fixed formula forever. Found a real gap: the *point prediction* (bias correction, fixed 2026-08-25) already adapts to real outcomes, but the *range width* around it was still raw ATR (±1x), never checked against how often reality actually landed inside it.

**Fix — `RangeCalibrator`**: an online adaptive-conformal-style update. After every evaluated prediction, nudge a multiplier on ATR up if the actual value fell outside the range, down (more gently) if it fell inside — sized so the long-run hit rate converges toward a 90% target. One multiplier per instrument+interval, tracked independently (new tiny table `range_calibration_state`, 4 rows total).

**Backtested first** (`RangeCalibrationBacktestService` / `DailyRangeCalibrationBacktestService`) against 2 years of real history, replaying the online update step by step, no lookahead:

| | Fixed ±1x ATR coverage | Adaptive coverage | Converged multiplier |
|---|---|---|---|
| NIFTY hourly | 85.62% | 89.58% | 1.29x |
| BANKNIFTY hourly | 86.62% | 89.67% | 1.23x |
| NIFTY daily | 90.55% | 91.17% | 0.89x |
| BANKNIFTY daily | 89.94% | 90.14% | 0.99x |

A genuinely useful, honest finding: the hourly range was quietly under-covering (only ~86% of outcomes landed inside it, well short of a reasonable 90%) — the adaptive multiplier fixes that concretely, at the cost of ~20% wider hourly ranges (an honest cost, not a flaw — reflects real uncertainty ATR alone understated). The daily range was already close to well-calibrated, so the calibrator correctly left it mostly alone.

**Deployed live**: both `ForecastPredictionService` and `DailyForecastPredictionService` now record whether each evaluated prediction was covered (updating the stored multiplier one step at a time) and scale the next prediction's range by the current multiplier. New endpoint `GET /api/forecast/{instrument}/range-calibration?interval=1h|1d` shows the live multiplier and when it last moved — starts at 1.0000 for all four combos until real evaluations accumulate. Migration `V3__add_range_calibration_state.sql` applied cleanly on deploy.

## Volume-confirmation backtest built 2026-08-26 — real finding: no volume data exists yet
Explored whether trading volume (already ingested via `OhlcvCandle.volume`, never used anywhere) could strengthen the 4 existing pattern signals — a classic TA idea: a pattern firing on unusually high volume is traditionally more reliable. Built `VolumeConfirmationBacktestService` (`GET /api/patterns/{instrument}/volume-confirmation-backtest?interval=1h`) to test this empirically rather than assume it: segments each pattern's historical occurrences into HIGH volume (≥1.5x the trailing 20-bar average), NORMAL, or UNKNOWN (missing/zero volume), and compares win rates per bucket.

**Real result**: every single occurrence across both instruments and all 4 patterns (770+ combined) fell into UNKNOWN — zero in HIGH, zero in NORMAL. Verified this is a genuine data limitation, not a pipeline bug (volume is correctly wired through `Candle` → `IngestionService` → `OhlcvCandle`): Upstox reports zero/null volume for the NIFTY/BANKNIFTY **index** candles themselves, since an index isn't a directly-traded instrument — only its futures/options contracts have real traded volume.

**Decision**: leave the backtest endpoint in place (harmless, dead until real volume data exists) rather than revert it. Revisit once futures or commodities data ever gets added — that's the real fix (switching to or additionally ingesting the NIFTY/BANKNIFTY futures contracts, which would need rollover handling since futures expire monthly, unlike the perpetual index) — not something to build speculatively now.

## Monte Carlo bootstrap range tested and rejected 2026-08-26
Tested whether bootstrap-resampling real historical returns (instead of assuming a symmetric ATR-based band) would produce better-calibrated prediction ranges than the current online-adaptive multiplier. Built `MonteCarloSimulator` + `MonteCarloBacktestService`/`DailyMonteCarloBacktestService`, backtested against 2 years of real history across both instruments and both timeframes.

**Result: no consistent improvement, one clear regression.** Hourly was roughly a wash (slightly narrower ranges, slightly lower coverage than the adaptive approach already live). BANKNIFTY daily was measurably worse — coverage dropped to 88.01% vs. the adaptive approach's 90.27%, for no error improvement. Same conclusion as when the momentum model lost to plain random walk: the added complexity (2000 simulations per prediction) didn't earn its keep against what's already live.

**Reverted** — all 4 new files deleted, `ForecastController` restored, redeployed. Confirmed live: `/monte-carlo-backtest` returns 404, everything else unaffected. Nothing about this experiment is documented in code (it's gone), only here — a clean example of "backtest before trust" catching a negative result before it ever reached production.

## What's built (all working, tested)
- Upstox broker adapter (candles + option chain), OAuth login flow
- Hourly ingestion + signal generation + outcome evaluation, fully automatic
- 4 patterns: EMA_BULLISH_CROSS, EMA_BEARISH_CROSS, RSI_OVERSOLD_REVERSAL, RSI_OVERBOUGHT_REVERSAL — backtested against 2 years of real data (73-385 occurrences per pattern, above the 30-sample trust threshold)
- **Rolling hourly forecast** (new 2026-08-17): predicts next hour's close ± range using a random-walk baseline (empirically beat a momentum-based alternative in backtesting — see `/api/forecast/{instrument}/backtest`) plus a significance-gated bias correction (2026-08-25 fix — see above; only corrects when recent error is statistically distinguishable from noise, and only partially even then). Runs automatically every hour alongside the pattern system, fully independent of it.
- 34 unit tests (all pass standalone; 1 separate DB-integration test needs a local Postgres that no longer runs since the cloud move — expected, not a bug)
- HTTPS via Caddy (auto-renewing Let's Encrypt cert) + DuckDNS free domain

## What's NOT built yet
- Frontend (React Native placeholder exists in `frontend/`, not built out) — deliberately deferred until after the Aug 25 review, which has now happened
- Phase 4: full backtest engine (walk-forward validation, transaction costs, drawdown) for the pattern system specifically — the forecast loops' rolling bias correction is effectively doing continuous recalibration already, just for the forecast models, not the pattern win rates
- PCR-based pattern (no historical option-chain data source exists to backtest one against)
- Notifications (Telegram/email) — would solve "how do I know without checking" more elegantly than manually hitting `/history`
- Arbitrary-stock long-horizon (2-3 month) range prediction — flagged as a much bigger, separate initiative (different math, needs new instrument lookup/backfill, different on-demand interaction pattern), not started, needs its own scoping discussion first
- Volume-confirmed pattern signals — backtest tooling exists (`/api/patterns/{instrument}/volume-confirmation-backtest`) but is currently a dead end: NIFTY/BANKNIFTY index candles carry no real volume data. Blocked on futures/commodities data ever being added (see below) — revisit then, not before

## Known constraints worth remembering
- Instances are tiny (1 OCPU/1GB RAM each, Always Free tier) — needed swap space added on both VMs to avoid OOM kills during package installs
- Cloud Shell sessions reset periodically and silently clear bash variables — always `echo $VAR` to check before reusing one after a gap
- SSH ProxyJump (`-J`) to the DB VM needs `ssh-agent` + `ssh-add` first, `-i` alone doesn't reliably forward through the jump host
- The free Ampere (ARM, bigger) shape has been consistently "out of capacity" in this region — don't bother retrying unless there's a specific reason to
- Both VMs are within Oracle's Always Free allowance (2x `VM.Standard.E2.1.Micro`) — confirmed via Oracle's own docs, genuinely costs nothing

## Redeploy procedure (for future code changes) — updated 2026-08-30, no more Cloud Shell
Cloud Shell's browser upload widget was unreliable over mobile data (stalled at random %, no resume). Replaced with a dedicated direct-SSH keypair (`~/.ssh/trading_vm_direct_key`, public half added to the VM's `authorized_keys`) so every redeploy goes straight from the local machine:
1. `cd backend && ./mvnw.cmd clean package -DskipTests` (builds `target/signal-backend-0.0.1-SNAPSHOT.jar`)
2. `scp -i ~/.ssh/trading_vm_direct_key target/signal-backend-0.0.1-SNAPSHOT.jar opc@140.245.255.99:~/signal-backend-0.0.1-SNAPSHOT.jar`
3. `ssh -i ~/.ssh/trading_vm_direct_key opc@140.245.255.99 "sudo systemctl restart trading-signal"`
4. Wait ~20-40s, then check `https://jerintradingsignal.duckdns.org/actuator/health`
5. Re-login to Upstox (`/auth/upstox/login-url`) — the access token lives in memory only and is wiped by every restart

## Longer-term next steps (not urgent, discuss when ready)
- Let both the pattern system and the forecast loop run live for real and accumulate real predicted-vs-actual data over time
- Consider Telegram/email notifications instead of manually checking `/history`
- Consider building out the frontend now that there's a stable public URL
- Longer-term scope discussed but not started: Sensex (needs separate BSE data source), options chains (flagged as the most likely thing to force a move off free tier), commodities (MCX, separate pipeline), eventual public mobile app (compliance/SEBI RA registration considerations noted if ever made public, not relevant for personal use)

## Update 2026-08-30 — Phase B and Phase C concluded (both negative, both real findings)
Since the 08-25 snapshot above: a mobile app (Expo/React Native) was built out (Dashboard/History/Signals/Analysis/Login tabs), futures ingestion was added (`NIFTY_FUT`/`BANKNIFTY_FUT`, unblocks the volume-confirmation backtest since the index itself carries no volume), Monte Carlo bootstrap resampling for prediction ranges was tested and reverted (no improvement over the simple ATR range), and the repo had its first-ever git commit (`e9a4406`) after removing a leaked Upstox API secret found in `.claude/settings.local.json`.

**Phase B (historical-analog daily forecast)** — concluded: neither a single-feature nor a richer 4-feature (return-so-far, volatility-so-far, RSI14, EMA9/21 spread) k-NN analog model beat the existing plain volatility-scaled re-anchor baseline at most hours. Kept live as documented findings: `GET /api/forecast/{instrument}/historical-analog-backtest`, `GET /api/forecast/{instrument}/rich-historical-analog-backtest`. Not wired into the live daily forecast.

**Phase C (multi-month momentum)** — concluded: no exploitable momentum at a 40-day horizon. First pass on NIFTY/BANKNIFTY alone was underpowered (~12 non-overlapping windows); pooled the test across a NIFTY-50 stock basket (49/50 symbols resolved and backfilled 2 years hourly — `TATAMOTORS` is the one holdout, likely the 2024-2025 demerger) to reach 614 non-overlapping-equivalent samples. Result: correlation -0.0304 (~zero), quartile buckets show a slight mean-reversion tilt rather than momentum, win rates ~50-54% everywhere. New endpoints, all kept live:
- `POST /api/ingestion/equity-basket/{symbol}/backfill` — single-symbol equity backfill (test before batch)
- `POST /api/ingestion/equity-basket/backfill` — full 50-symbol basket backfill
- `GET /api/forecast/{instrument}/momentum-backtest` — single-instrument momentum test
- `GET /api/forecast/pooled-momentum-backtest` — the pooled/basket version (no `{instrument}` — pools NIFTY+BANKNIFTY+basket)

Net effect across the whole session: four independent tests (short-horizon momentum-vs-random-walk, Monte Carlo bootstrap, historical-analog, multi-month momentum) all confirm the deterministic/classical-stats toolkit finds no exploitable edge at any horizon tried so far on NIFTY/BANKNIFTY. Full detail and reasoning in project memory (`project_trading_signal_longterm_vision.md`, not in this repo).

Committed as `2cd6721` (Phase B + C backend work).

## Update 2026-08-30 — Phase D started: live basket monitoring (not signals yet)
Phase D's first cut, scoped deliberately narrow: keep the NIFTY 50 basket's data live (the hourly job now also calls `EquityBasketIngestionService.ingestTodayForBasket()` each cycle, isolated try/catch like futures) and expose a read-only monitoring snapshot — `GET /api/monitor/basket` — with latest close, % change, EMA9/21 trend, and RSI14 zone for NIFTY, BANKNIFTY, and the basket.

Deliberately did NOT extend `PatternStatsService`/`SignalService` (win-rate-backed pattern signals) to the basket: `pattern_stats` is keyed only by `pattern_id`, not by instrument (a real pre-existing schema gap), so recomputing stats against 49 more stocks under the same key would silently corrupt NIFTY/BANKNIFTY's already-trusted confidence tiers. Fixing that needs a schema migration first — flagged for whenever real per-instrument pattern signals are wanted, not done in this pass.

Mobile app: new "Monitor" tab (sortable by top gainers / top losers / A-Z) consuming the new endpoint. Added web support (`react-dom`, `react-native-web`) so the app can be smoke-tested in a browser during development — confirmed the new screen renders and fires the correct API call; full data rendering wasn't verifiable in-browser because the backend has no CORS headers configured (native app doesn't need them), which affects every existing screen identically, not just the new one.

Committed as `a8ea548`. Next: decide whether Phase D continues with the PatternStats schema fix (to get real signals on the basket) or moves to commodities/options per the roadmap.

## Update 2026-08-30 (later same day) — per-stock intraday close/high/low prediction, live for all 50 basket stocks
Jerin asked why the app didn't show a per-stock predicted close/high/low (e.g. select SBILIFE, see today's predicted close, tracked live through the day, evaluated at end of day, feeding tomorrow's correction) and separately asked for a "monthly" prediction tab. Corrected an important misunderstanding first: Phase C's momentum backtest didn't fail to get displayed — it tested exactly "does past return predict future return" and found no real signal (correlation ~0 across 614 samples). Building a "predicted monthly closing price" number would misrepresent that finding, so nothing was shipped for the monthly tab; per Jerin's own choice it's parked as a research question (does a different feature set — e.g. the mean-reversion tilt the momentum data hinted at — hold up as a real signal?), not a display task.

The **intraday** ask was legitimate and got built: generalized `DailyForecastPredictionService`/`DailyTrajectoryService` (previously hard-typed to the `Instrument` enum) to take a plain instrument-tag string instead. Safe to do because `hourly_predictions` is genuinely keyed by (instrument, interval, predicted_for_ts) — unlike `pattern_stats`, there's no cross-instrument corruption risk here. The hourly job now runs the same-day predict/evaluate cycle for every successfully-ingested basket stock each cycle (isolated per-stock try/catch), exactly the same model already validated on NIFTY/BANKNIFTY: random-walk baseline + gated bias correction + self-calibrating range width (the range IS the predicted low/high, not a separate mechanism).

`GET /api/monitor/basket` now also returns each stock's `predictedClose`/`predictedRangeLow`/`predictedRangeHigh`/`deviationFromPredictionPct` (null until that stock's first prediction is recorded). Mobile app: tapping any row in the Monitor tab pushes `/stock/{symbol}`, a new screen reusing the existing `TrajectoryChart` component to show the live intraday call for that stock; added a "Biggest move vs. call" sort mode to the Monitor list.

Since today (2026-08-30) is a Sunday, this couldn't be verified end-to-end with live market data — the basket stocks' `predictedClose` will start populating automatically once the hourly job runs during the next trading session (it only needs that day's first candle to exist, which the already-live basket ingestion provides). Verified structurally: endpoints return correct shapes, NIFTY/BANKNIFTY (which already had predictions) show real non-null values, basket stocks correctly show null pending Monday's data.

Committed separately from the Phase D monitoring commit above.

## Update 2026-08-30 (later still) — per-stock Analysis section, same walk-forward backtests as NIFTY/BANKNIFTY
Jerin asked whether selecting a stock also shows "the analysis, all the things you have added" — i.e. the same walk-forward evidence (model choice, bias correction, range calibration, intraday re-anchoring) already shown in the mobile Analysis tab for NIFTY/BANKNIFTY. It didn't yet, so generalized the remaining same-day/intraday backtest services (`DailyForecastBacktestService`, `DailyBiasCorrectionBacktestService`, `DailyRangeCalibrationBacktestService`, `IntradayReanchorBacktestService`) from the `Instrument` enum to a plain string tag — these were even lower-risk than the earlier daily-forecast generalization since they're pure read-only recomputations with no persistence at all. Deliberately left the *hourly*-loop backtests (`ForecastBacktestService`, `BiasCorrectionBacktestService`, `RangeCalibrationBacktestService`) untouched — there's no hourly forecast loop running for basket stocks (only same-day), so an hourly backtest for them would have nothing real to validate.

The stock detail screen (`app/stock/[symbol].tsx`) now has an "Analysis" section below the trajectory chart, reusing the same `BacktestResultTable`/`IntradayReanchorTable` components from the Analysis tab. Verified against real data — SBILIFE (already backfilled from Phase C) returns sane, real results: 481 samples, random walk beats the EMA-momentum model (0.948% vs 0.996% mean error), gated bias correction ≈ no correction, adaptive range multiplier converges to 0.978x, and intraday re-anchoring dramatically helps on "big morning miss" days (50% → 100% coverage) — same pattern already found for NIFTY/BANKNIFTY, now confirmed to hold on an individual stock too.

## Update 2026-08-30 (evening) — Jerin questioned the intraday re-anchor mechanism; resolved with fresh per-stock evidence, then started ML groundwork
Jerin asked why the "live re-anchored" number during the day isn't doing real pattern/chart-shape analysis — correctly observing that it's literally just "assume the current price holds, shrink the uncertainty band as the day runs out." That's accurate, not a bug: it's the validated result of Phase B (k-NN historical-analog matching on return-so-far, RSI, EMA spread, volatility-so-far) losing to this naive approach. That was previously only shown for NIFTY/BANKNIFTY — generalized `HistoricalAnalogBacktestService`/`RichHistoricalAnalogBacktestService` to arbitrary instrument tags and re-ran it fresh against SBILIFE specifically: same result holds independently (479 samples, baseline beats both analog variants at every intraday hour). Real, stock-specific confirmation, not an assumption carried over from the index-level backtests.

Jerin then made a longer-term point: since ML is on the roadmap eventually (per [[project_trading_signal_longterm_vision]]), the project should start laying groundwork now rather than scrambling to backfill history once that phase starts. Important distinction drawn and agreed: proving a *simple/manual* pattern-matching rule has no edge doesn't mean the underlying features are useless to a future ML model — a model can find nonlinear interactions a k-NN average cannot. So rather than wiring a fake "smarter" prediction into the live app (which the evidence argues against), built a **separate, non-live feature/label dataset pipeline**:

- New table `ml_feature_snapshots` (migration `V4__add_ml_feature_snapshots.sql`) — one row per (instrument, trading day): OHLCV, daily return, gap-from-prev-close, intraday range, EMA9/21 + spread, RSI14, ATR14, lookback returns (5/10/20/40-day), and **forward-looking** return labels at the same four horizons, nullable until that many trading days have actually passed (no lookahead).
- `MlFeatureSnapshotService`/`Controller` (`com.jerin.trading.ml` package) — recomputes and upserts fresh from stored candle history each call, same convention as every backtest service in this codebase. Manual endpoints only for now (`POST /api/ml/features/{instrument}/backfill`, `POST /api/ml/features/backfill-all`, `GET /api/ml/features/{instrument}`) — deliberately NOT wired into the live hourly job yet, to avoid unnecessary DB load on the tiny free-tier VM before it's clear how often this actually needs refreshing once real training work starts.
- Backfilled immediately for NIFTY + BANKNIFTY + the 49 resolved basket stocks: **23,344 rows** written in one call. Spot-checked SBILIFE's data — sane and correctly shaped (e.g. RSI 23 correctly paired with negative recent returns and negative EMA spread; forward-return labels correctly filled only where enough time has actually passed).

Explicitly not built yet, and not implied by this: no model training, no inference, no change to any live prediction. This is purely a growing labeled dataset sitting in the DB, ready for whenever actual ML work starts.

## Update 2026-08-30 (night) — first real ML model trained and rigorously tested; both results negative
Jerin pushed further and asked for an actual trained model, not just deterministic k-NN. Set this up properly:

- **Backend**: added `GET /api/ml/intraday-features/{instrument}` and `/all` (`IntradayFeatureExportService`/`Controller`) — raw per-(day,hour) feature+outcome export, same feature set as `ChartShapeAnalogBacktestService`, for offline training.
- **Local tooling**: `Tradingproject/ml-training/` (git-tracked scripts; `data/` and `venv/` gitignored — moved out of the session temp scratchpad and off the C: drive per Jerin's explicit request, since C: was congested). Set up with `python -m venv venv` + `venv\Scripts\pip install -r requirements.txt` (numpy, pandas, scikit-learn, lightgbm — all free/open source). Scripts: `download_intraday_features.py`, `download_ml_features.py`, `train_intraday_model.py`, `train_multiday_model.py`, `validate_multiday_walkforward.py`.
- **Intraday same-day-close**: LightGBM trained on 133,077 rows (51 instruments), proper chronological holdout (test = genuinely unseen future dates). Ties the baseline (0.4522% vs 0.4490% error), 51.6% direction accuracy. 4th independent method to fail here (after 3 deterministic k-NN variants) — treat as closed, high-confidence negative for this feature family.
- **Multi-day forward returns (5/10/20/40-day)**: first single-split test looked promising at 20d/40d (ML beat baseline, correlation up to 0.146) — but re-checked immediately across 4 independent chronological folds (`validate_multiday_walkforward.py`) and it did **not** hold up: ML won only 1/4 folds at both horizons, correlation flips sign fold to fold, direction accuracy noise (46-52%). The promising split was luck, not a discovery — correctly caught by re-validating before trusting it, exactly the discipline this project has used all along.

**Net conclusion**: after everything tried this session (4 intraday methods, 2 multi-day horizons across multiple validation windows), there is no validated ML or deterministic signal in price/technical-derived features (OHLCV, RSI, EMA, ATR, candle shape) at any horizon tested. A real edge, if one exists, likely needs a genuinely different data source — not more feature engineering on the same candles. Full detail in project memory (`project_trading_signal_longterm_vision.md`).

## Update 2026-08-30 (later still) — volume and cross-sectional relative strength, both also negative
Jerin asked directly what could overcome random walk; picked "volume+options, then cross-sectional relative strength" via AskUserQuestion.

- **Volume**: real trade volume exists for all 49 basket stocks (never used as a feature before). Added `volumeSoFarRatio`/`volumeRatio20d` (today's volume vs trailing 20-day average) to both ML datasets. Result: intraday — ranked the #1 most important feature by the model, yet accuracy unchanged; multi-day — made results slightly worse.
- **Options/PCR**: checked first — only ~2 weeks of option-chain history exists (vs 2+ years of OHLCV), far too little for a rigorous test. Deferred, not abandoned; revisit once more history accumulates.
- **Cross-sectional relative strength** (`ml-training/test_cross_sectional_relative_strength.py`): does a stock's performance *relative to* the other 50 predict its future *relative* performance? Correlation ~0 (slightly negative at 3/4 horizons), ML beats baseline in 0/4 or 1/4 folds depending on horizon — no real signal.

Six methods now tested (intraday k-NN ×3, real ML, multi-day absolute and relative framings, with/without volume), all negative, all validated with genuine multi-fold walk-forward, not single-split luck. Price/technical features from this system's own OHLCV data are effectively exhausted — further progress needs a different data source (fundamentals, options once enough history exists, or global/overnight data for the gap problem specifically), not more feature engineering on the same candles.
