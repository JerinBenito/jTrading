-- pattern_stats was keyed by (pattern_id, window_end) only, with no instrument column, so
-- recomputing it for a second instrument silently overwrote the first's win-rate stats
-- whenever their window_end dates coincided (which they usually do, since instruments ingest
-- on the same schedule) — SignalService picked up whichever instrument's numbers were written
-- last, regardless of which instrument's pattern actually just fired. This is exactly what was
-- blocking real per-instrument win-rate signals for the NIFTY 50 basket stocks.
--
-- Fully recomputable from stored candle data (PatternStatsService.recomputeAll rebuilds every
-- row from scratch on every call — it is a derived cache, not a point-in-time prediction
-- ledger), so truncating and re-keying loses nothing that can't be regenerated.
TRUNCATE TABLE pattern_stats;
ALTER TABLE pattern_stats DROP CONSTRAINT pattern_stats_pkey;
ALTER TABLE pattern_stats ADD COLUMN instrument VARCHAR(50) NOT NULL;
ALTER TABLE pattern_stats ADD PRIMARY KEY (pattern_id, instrument, window_end);
