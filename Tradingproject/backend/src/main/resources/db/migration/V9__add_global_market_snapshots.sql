-- Overnight global-market context (US indices, crude oil, USD/INR) fetched once daily shortly
-- before Indian market open. Purely observational for now, same discipline as every other new
-- data source in this codebase (option chain / PCR sat unused as a passive indicator for weeks
-- before ever becoming a signal) — accumulates real history first, gets tested as an actual
-- feature/signal only once there's enough of it to backtest honestly.
CREATE TABLE global_market_snapshot (
    id BIGSERIAL PRIMARY KEY,
    -- SP500, DOW, NASDAQ, CRUDE_OIL, USD_INR
    symbol VARCHAR(20) NOT NULL,
    -- the IST trading date this snapshot represents (the pre-market read for that day)
    trading_date DATE NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,

    price NUMERIC(14,4) NOT NULL,
    change_pct NUMERIC(8,4),
    previous_close NUMERIC(14,4),

    UNIQUE (symbol, trading_date)
);
