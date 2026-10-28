-- Hourly summary of each basket stock's option chain (nearest monthly expiry at least 5 days out): at-the-money implied vol,
-- put-vs-call skew, put/call open-interest ratio and the at-the-money straddle price. Recorded from 2026-10-28 because the broker
-- only serves the CURRENT chain (no history), and implied vol was the single best input found for the next-day / same-day range
-- models. One compact row per stock and capture, not the whole chain.
CREATE TABLE stock_option_summaries (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    expiry DATE NOT NULL,
    days_to_expiry INT NOT NULL,
    spot NUMERIC(14,4),
    atm_strike NUMERIC(14,4),
    call_iv NUMERIC(10,4),
    put_iv NUMERIC(10,4),
    atm_iv NUMERIC(10,4),
    skew NUMERIC(10,4),
    pcr_oi NUMERIC(10,4),
    total_oi BIGINT,
    straddle_pct NUMERIC(10,4)
);

CREATE INDEX idx_stock_option_summaries_inst_ts ON stock_option_summaries (instrument, captured_at);
