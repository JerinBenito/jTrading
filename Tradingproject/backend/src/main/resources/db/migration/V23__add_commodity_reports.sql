-- MCX commodity report (gold, silver, crude, natural gas, copper, aluminium): contracts with expiry dates, term structure,
-- option-implied moves and volatility-scaled peak/dip ranges. Built by ml-training/commodity_report.py and pushed here;
-- stored as the JSON text it was built as, so the shape can evolve without migrations. Descriptive risk, not recommendations.
CREATE TABLE commodity_reports (
    id BIGSERIAL PRIMARY KEY,
    report_date DATE NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
