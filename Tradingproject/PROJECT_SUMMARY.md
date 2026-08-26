# NSE Trading Signal & Forecast System

*Personal project — deterministic market analysis platform for NSE index trading*

---

## One-line summary
A self-hosted, deterministic market-analysis system for the Indian stock market (NSE) that ingests live price data hourly, generates statistically-backed trading signals from hand-defined technical patterns, and runs a self-correcting one-hour-ahead price forecast — architected, built, and deployed end-to-end on free-tier cloud infrastructure.

---

## Resume bullet points (pick and adapt as needed)

- Designed and built a full-stack financial data pipeline in Java/Spring Boot and PostgreSQL that ingests real-time market data via a broker REST API, computes technical indicators (EMA, RSI, ATR, PCR), and evaluates a hand-defined pattern library against 2+ years of historical data, with backtested win-rate validation per signal (73–385 historical occurrences per pattern)
- Designed and shipped a self-correcting one-hour-ahead price forecasting system: walk-forward backtested two candidate statistical models against real market history, selected the empirically superior one, and implemented a rolling bias-correction loop that adjusts live predictions based on the model's own recent errors — achieving under 0.1% average error in live production
- Architected and deployed a two-tier cloud system (isolated database + application VMs) on Oracle Cloud Infrastructure at zero operating cost, independently configuring Linux server administration, PostgreSQL, systemd service management, HTTPS (Caddy + Let's Encrypt + dynamic DNS), and cloud networking/firewall rules
- Integrated a third-party broker's OAuth2 API for live market data, handling daily-expiring credentials, token refresh flow, and resilient error recovery so the pipeline degrades gracefully instead of failing silently
- Diagnosed and resolved multiple production data-pipeline defects through direct root-cause investigation (database verification, log analysis, API documentation review) rather than surface-level fixes — including a silent zero-data ingestion bug traced to incorrect broker API endpoint selection
- Built a 34-test unit test suite validating deterministic financial calculations (technical indicators, pattern detection logic, forecast models) against independently hand-computed reference values
- Enforced strict engineering constraints throughout: fully deterministic pipeline (no AI/ML black boxes), no data leakage in backtests, no fabricated precision in outputs (confidence tiers backed only by real historical sample sizes)

---

## Technical stack
**Backend:** Java 21, Spring Boot, Spring Data JPA, Quartz Scheduler, Flyway (schema migrations)
**Database:** PostgreSQL
**Infrastructure:** Oracle Cloud Infrastructure (2× Linux VMs), systemd, Caddy (reverse proxy + automatic TLS), Let's Encrypt, DuckDNS
**External integration:** Upstox broker REST API (OAuth2, market data, option chain)
**Testing:** JUnit 5, AssertJ
**Tooling:** Maven, Git

---

## System architecture

```
Upstox Broker API (market data, OAuth2)
        │
        ▼
┌─────────────────────────────┐        ┌──────────────────────┐
│   App VM (public, HTTPS)    │───────▶│   DB VM (private)     │
│   Spring Boot backend       │        │   PostgreSQL 16       │
│   - Quartz hourly scheduler │        │   - candles/options   │
│   - REST API                │        │   - predictions        │
└─────────────────────────────┘        │   - pattern stats      │
                                        └──────────────────────┘
```

Runs unattended during market hours (weekdays, 9am–3pm IST): each cycle ingests fresh data, checks pattern conditions, evaluates prior predictions against actual outcomes, and generates a new forecast — with only a single manual daily login required (the broker enforces daily-expiring tokens with no non-interactive auth option).

---

## Key engineering decisions worth discussing in an interview

1. **Deterministic by design, not ML.** Every prediction traces back to inspectable statistics — technical indicators and historical win rates — rather than an opaque model. This was a deliberate constraint, not a limitation: it keeps every output auditable and avoids fabricated confidence.

2. **Backtested before trusted.** Built two candidate forecast models (a naive random-walk baseline and a momentum-adjusted variant), backtested both against real historical data with strict walk-forward validation (no lookahead bias), and let the data pick the winner — the simpler model won, which itself was a useful, evidence-based finding rather than an assumption.

3. **Root-cause debugging under real production pressure.** When live data mysteriously stopped flowing, traced it through log analysis and direct database inspection to a subtle API-selection bug (asking a "historical data" endpoint for "today," which silently returns nothing rather than erroring) — and fixed it by adding the correct endpoint rather than working around the symptom.

4. **Cost-conscious infrastructure engineering.** Delivered a fully cloud-hosted, always-on system at zero recurring cost by carefully working within free-tier cloud constraints (1 vCPU / 1GB RAM per VM) — including diagnosing and fixing out-of-memory failures during setup by adding swap space, and splitting the database and application across two VMs to stay within resource limits.

5. **Operational resilience over cleverness.** Every component is designed to fail gracefully and log clearly rather than crash — a broken token, a missing data point, or an API hiccup on one instrument never takes down the other instrument's pipeline or the whole system.

---

## Scope & constraints (for context if asked)
Personal-use only, not a commercial product — output is informational only (direction + confidence + range, never trading instructions or exact price targets), and all trading decisions remain manual. Currently covers NIFTY and BANKNIFTY indices; architecture is designed to extend to additional instruments.
