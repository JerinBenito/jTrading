# Indian Market Statistical Analysis & Signal System

A personal, deterministic (no AI/LLM in the runtime pipeline) statistical trading-analysis
tool for NSE — NIFTY, BANKNIFTY, options, futures, and select stocks. Ingests market data,
computes indicators/patterns in code, generates directional probability signals backed by
historically-computed win rates, tracks outcomes, and periodically recalibrates. It does not
place trades — all trading decisions are made manually.

## Structure

- `backend/` — Spring Boot (Java 21). Data ingestion, indicator engine, pattern/signal logic,
  backtesting, REST API. See `backend/pom.xml` for the module's dependencies.
- `frontend/` — Expo/React Native placeholder. Intentionally minimal — real dashboard work
  starts after the backend is validated (see `frontend/README.md`).
- `docker-compose.yml` — local PostgreSQL for development.

## Hard constraints

1. No AI/LLM calls anywhere in the ingestion, indicator, prediction, or backtest pipeline.
2. Never output an exact predicted price — only direction, a confidence tier backed by a real
   sample size, and an expected range.
3. No data leakage: any "as of" statistic must only use data strictly before that date.
4. One canonical table per time-series concept (see `db/migration`), not separate
   historical/current tables.
5. Broker API is the primary data source; NSE scraping is a fallback only, isolated behind its
   own adapter.
6. Every prediction is persisted with its inputs so it can be checked against its outcome later.

## Getting started

```bash
# 1. Start Postgres
docker compose up -d

# 2. Set Upstox credentials (see "Broker: Upstox" below)
export UPSTOX_API_KEY=...
export UPSTOX_API_SECRET=...

# 3. Run the backend (applies Flyway migrations on boot)
cd backend
./mvnw spring-boot:run
```

The backend reads DB connection settings from `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` /
`DB_PASSWORD` env vars (see `backend/src/main/resources/application.yml`), defaulting to the
credentials in `docker-compose.yml`.

## Broker: Upstox

Chosen for Phase 1 — free developer API access (unlike Zerodha's Kite Connect, which charges
₹2000/month), documented historical candle and option chain endpoints.

1. Create an app at the [Upstox developer console](https://account.upstox.com/developer/apps)
   with redirect URI `http://localhost:8080/auth/upstox/callback` (or set `UPSTOX_REDIRECT_URI`
   to match if you use a different one).
2. Set `UPSTOX_API_KEY` / `UPSTOX_API_SECRET` env vars from that app.
3. **Upstox access tokens expire daily** — there's no password grant, only browser login. Each
   trading day:
   - `GET http://localhost:8080/auth/upstox/login-url` → open the returned URL in a browser,
     log in with your Upstox credentials + TOTP.
   - Upstox redirects to the callback URL with a `code` param — call
     `GET http://localhost:8080/auth/upstox/callback?code=...` to exchange it for an access
     token (held in memory for the process's lifetime).
4. `UpstoxBrokerClient` (`backend/src/main/java/com/jerin/trading/broker/upstox/`) implements
   the `BrokerClient` interface — candles via `/v3/historical-candle`, option chain (OI, IV,
   LTP, volume) via `/v2/option/chain`. `BrokerClient` is broker-agnostic by design (per hard
   constraint #5) — swapping brokers later means writing a new adapter, not touching callers.

## Current status

Schema + backend skeleton + Upstox broker adapter with OAuth login flow + hourly ingestion job
(Quartz, `com.jerin.trading.ingestion`, weekdays 9am-3pm IST) pulling candles and current-week
option chain for NIFTY and BANKNIFTY into Postgres, deduped on `(instrument, interval, ts)`.
Requires a valid Upstox access token in memory (see "Broker: Upstox" above) to actually fetch
data; the job logs and continues if a token is missing or a fetch fails.

Indicator engine (`com.jerin.trading.indicator`) computes EMA(9), EMA(21), RSI(14), ATR(14) —
pure functions over stored candles, Wilder smoothing for RSI/ATR — and PCR from the latest
option chain snapshot. Values are null (not fabricated) until enough candles exist. Check via
`GET /api/indicators/NIFTY?interval=1h` once data is flowing.

Pattern library (`com.jerin.trading.pattern`) has 4 hand-defined patterns computable purely from
OHLCV — `EMA_BULLISH_CROSS`, `EMA_BEARISH_CROSS`, `RSI_OVERSOLD_REVERSAL`, `RSI_OVERBOUGHT_REVERSAL`.
(No PCR-based pattern yet — Upstox has no historical option chain endpoint, only live, so there's
no way to backtest one; that becomes possible once we've accumulated our own option chain history.)

- `POST /api/patterns/{instrument}/recompute?interval=1h` — scans full backfilled history, computes
  each pattern's win rate and average move over a 5-bar holding period, upserts `pattern_stats`.
- `GET /api/patterns/stats` — current stats for all patterns.
- `GET /api/signals/{instrument}?interval=1h` — checks if any pattern fires on the latest bar;
  only emits a signal if that pattern already has real historical stats (never a fabricated
  confidence tier), persists it to `signal_predictions` with a full inputs snapshot.
- `POST /api/signals/{instrument}/evaluate-outcomes?interval=1h` — fills in `signal_outcomes` for
  past predictions once 5 bars have elapsed since they fired.

All four endpoints tested against an empty database (before real data exists) — they behave
correctly, returning empty results rather than erroring. Not yet tested against real historical
data, since that still needs the Upstox account approval to backfill anything.

Still not built: the full walk-forward backtest engine (Phase 4, deliberately deferred), the
weekly/monthly recalibration job (Phase 5), and the frontend dashboard.

## Open decisions

- Instrument list beyond NIFTY/BANKNIFTY for later phases.
- Hosting target (cloud vs self-hosted).

See full build order and phase-by-phase plan in project memory / prior conversation.
