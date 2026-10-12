-- One end-of-day market screen per trading date: market context, per-stock evidence dossiers and the
-- day's pre-registered hypotheses (with last session's hypotheses scored). Built by
-- ml-training/daily_screen.py after the close and pushed here; stored as the JSON text it was built as, so
-- the shape can evolve without migrations. Descriptive statistics, not recommendations.
CREATE TABLE daily_screens (
    id BIGSERIAL PRIMARY KEY,
    screen_date DATE NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
