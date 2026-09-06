-- V12 declared recent_pattern_direction as SMALLINT, but the mapped Java field is a plain
-- Integer with no columnDefinition override, so Hibernate's schema validation expects INTEGER
-- (int4) and fails startup against the SMALLINT (int2) column V12 actually created. Widening,
-- not narrowing, so no data loss risk (the column is brand new and empty anyway).
ALTER TABLE ml_feature_snapshots ALTER COLUMN recent_pattern_direction TYPE INTEGER;
