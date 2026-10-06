-- Quarterly results (earnings) dates per basket stock, pushed in by the daily AI run (Yahoo via
-- yfinance). Past dates are actual; future dates are the data vendor's announced/estimated dates and
-- can move, so the UI labels them "expected". Used for the "results today/soon" flag and as an input
-- to the same-day model: on a results day the model's 90% range under-covered (76% vs a 90% target)
-- until it was given this calendar (results_window_ablation.py, 2026-10-06).
CREATE TABLE results_calendar (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    results_date DATE NOT NULL,
    eps_estimate NUMERIC(12,4),
    fetched_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_results_calendar UNIQUE (instrument, results_date)
);

CREATE INDEX idx_results_calendar_date ON results_calendar (results_date);
