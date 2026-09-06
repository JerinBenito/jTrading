-- Wires every other model/data source this codebase has built (deterministic/HMM/GARCH daily
-- calls, options PCR, overnight global market context, company fundamentals, and the most
-- recent pattern signal's track record) into the AI model's training features, alongside the
-- existing pure price/candle-derived ones. See SupplementaryFeatureService. Nullable throughout:
-- most of these sources are only days old as of 2026-09-06, so most historical rows will start
-- out null here.
ALTER TABLE ml_feature_snapshots
    ADD COLUMN deterministic_deviation_pct NUMERIC(10,4),
    ADD COLUMN hmm_deviation_pct NUMERIC(10,4),
    ADD COLUMN garch_range_width_pct NUMERIC(10,4),
    ADD COLUMN pcr_latest NUMERIC(10,4),
    ADD COLUMN global_sp500_change_pct NUMERIC(10,4),
    ADD COLUMN global_crude_oil_change_pct NUMERIC(10,4),
    ADD COLUMN global_usd_inr_change_pct NUMERIC(10,4),
    ADD COLUMN fundamental_pe NUMERIC(10,4),
    ADD COLUMN fundamental_roe NUMERIC(10,4),
    ADD COLUMN recent_pattern_win_rate NUMERIC(6,2),
    ADD COLUMN recent_pattern_direction SMALLINT;
