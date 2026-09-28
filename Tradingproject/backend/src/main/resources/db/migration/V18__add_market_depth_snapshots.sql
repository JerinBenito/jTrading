-- Live order-book snapshots (see BrokerClient#getMarketQuotes) — captured periodically going
-- forward only, since Upstox's market-quote endpoint has no historical equivalent. Purely
-- observational; nothing reads this table yet.
CREATE TABLE market_depth_snapshots (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    ts TIMESTAMPTZ NOT NULL,

    last_price NUMERIC(14,4),
    total_buy_quantity BIGINT,
    total_sell_quantity BIGINT,
    imbalance NUMERIC(8,4),

    top_bid_price NUMERIC(14,4),
    top_bid_quantity BIGINT,
    top_ask_price NUMERIC(14,4),
    top_ask_quantity BIGINT
);

CREATE INDEX idx_market_depth_instrument_ts ON market_depth_snapshots (instrument, ts);
