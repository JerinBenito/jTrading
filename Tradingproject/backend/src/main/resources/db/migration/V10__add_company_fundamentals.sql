-- Company fundamentals (P/E, P/B, ROA, ROE, ROCE, EV/EBITDA, ...) for the NIFTY 50 basket
-- stocks, from Upstox's Company Fundamentals API (verified live and free, keyed by ISIN).
-- Upsert-in-place, not a changelog: fundamentals barely move day to day (quarterly reporting),
-- so this tracks the current known value per ratio rather than accumulating a near-duplicate
-- row every single fetch. Values stored as-is (VARCHAR) since Upstox mixes plain numbers
-- ("19.97") and percentages ("8.94%") in the same field — parsing into numeric types can be
-- added later if/when an actual feature needs it.
CREATE TABLE company_fundamental (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    isin VARCHAR(20) NOT NULL,
    ratio_name VARCHAR(50) NOT NULL,
    company_value VARCHAR(20),
    sector_value VARCHAR(20),
    fetched_at TIMESTAMPTZ NOT NULL,

    UNIQUE (symbol, ratio_name)
);
