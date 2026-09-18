package com.jerin.trading.ml;

/**
 * Everything else this codebase knows about an instrument on a given trading day, beyond plain
 * price/candle data — the deterministic/HMM/GARCH models' own calls, options PCR, overnight
 * global market context, company fundamentals, and the most recent pattern signal's track
 * record — joined by {@link SupplementaryFeatureService} so both {@link IntradayFeatureExportService}
 * and {@link MlFeatureSnapshotService} can feed the same enriched feature set to the AI model's
 * training. Every field is nullable: most of these data sources are only days old as of
 * 2026-09-06, so most historical rows will have nulls here for a while — LightGBM handles
 * missing values natively, and that's the honest state of the data, not a bug to paper over.
 */
public record SupplementaryFeatures(
        /** (deterministic model's predicted close - that day's open) / open * 100 — what the
         * classical random-walk+bias-correction model expected for today's drift. */
        Double deterministicDeviationPct,
        /** Same shape, from the HMM regime track (unvalidated — see DailyHmmRegimeModel). */
        Double hmmDeviationPct,
        /** GARCH's predicted range width as % of price — its actual point of view is volatility,
         * not direction, so this (not a deviation) is the informative half of that model. */
        Double garchRangeWidthPct,
        /** Put-call ratio by open interest, most recent snapshot at or before this day — only
         * ever populated for NIFTY/BANKNIFTY (options aren't tracked for basket stocks), and
         * still sparse (~months of real history as of 2026-09-06). */
        Double pcrLatest,
        Double globalSp500ChangePct,
        Double globalCrudeOilChangePct,
        Double globalUsdInrChangePct,
        /** Current fundamentals (P/E, ROE), applied uniformly to every historical row for this
         * instrument — NOT a real point-in-time value on older rows (we only keep the latest
         * known fundamentals, not a historical series), so this carries mild lookahead bias on
         * history. Fundamentals move slowly (quarterly at most) so the practical effect is
         * small, but it's a real, deliberate simplification worth knowing about, not a clean
         * feature. Only ever populated for basket stocks — NIFTY/BANKNIFTY are indices, not
         * companies, so they have no fundamentals at all. */
        Double fundamentalPe,
        Double fundamentalRoe,
        /** The most recent pattern signal's win rate and direction as of (at or before) this
         * day — genuinely point-in-time correct, since signal_predictions.inputs_snapshot
         * captured exactly what was known at the moment that signal fired. Direction is +1 for
         * "up", -1 for "down", null if no pattern has ever fired yet for this instrument. */
        Double recentPatternWinRate,
        Integer recentPatternDirection,
        /** How wrong the AI's own INTRADAY prediction was on the most recent day STRICTLY before
         * this one that's already been evaluated (as % of that day's actual close) — added
         * 2026-09-17 per an explicit request that the model see its own recent error, the way an
         * ARIMA model's moving-average term uses past forecast errors. Deliberately excludes the
         * day itself: at prediction time its own outcome isn't knowable yet, only what happened
         * before it. Null until at least one prior day has been evaluated. */
        Double aiPriorDayErrorPct,
        /** Whether that same prior-day prediction got the direction right, as 1/0 rather than a
         * boolean for LightGBM's benefit — null under the same conditions as the error above. */
        Integer aiPriorDayDirectionCorrect,
        /** How far, and in which direction, the AI moved its own prediction between its first and
         * last call on that same prior day: (last - first) / first * 100. SIGNED — positive means it
         * revised upward as the day went on, negative downward — since the direction of the drift,
         * not just its size, is the part that can carry information. Built from the stored
         * per-call deviations (ai_prediction_snapshots). Null until a prior day has valid
         * (pre-close) snapshots. */
        Double aiPriorDayRevisionGradientPct,
        /** The FIRST call's signed error on the prior evaluated day, as % of that day's actual
         * close: (first predicted - actual) / actual * 100. Positive means the AI's morning call was
         * too high, negative too low. This is the fair test of the AI — the first call is made with
         * the least information — unlike aiPriorDayErrorPct, which scores the near-close call.
         * Null until a day with a valid pre-close first call has been evaluated. */
        Double aiPriorDayFirstCallErrorPct
) {
    static final SupplementaryFeatures EMPTY = new SupplementaryFeatures(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
}
