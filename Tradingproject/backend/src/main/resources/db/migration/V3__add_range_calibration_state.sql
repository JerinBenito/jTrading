CREATE TABLE range_calibration_state (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    interval VARCHAR(10) NOT NULL,
    multiplier NUMERIC(6,4) NOT NULL DEFAULT 1.0,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (instrument, interval)
);
