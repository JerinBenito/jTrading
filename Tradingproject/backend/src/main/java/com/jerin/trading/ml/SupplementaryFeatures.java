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
        Integer recentPatternDirection
) {
    static final SupplementaryFeatures EMPTY = new SupplementaryFeatures(
            null, null, null, null, null, null, null, null, null, null, null);
}
