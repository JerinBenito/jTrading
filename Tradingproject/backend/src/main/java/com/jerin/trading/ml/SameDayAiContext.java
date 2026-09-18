package com.jerin.trading.ml;

/**
 * What the AI's OWN earlier calls today looked like, as known at the moment it is about to make
 * its next one — the model's view of its own recent behaviour. Only calls from strictly earlier
 * hourly checkpoints are visible; the call being made right now can't be (that would be circular).
 * Everything is null for the day's first call, and for any day before per-call snapshots existed
 * (2026-09-17), so expect these to be empty for most historical training rows for a long while.
 */
public record SameDayAiContext(
        /** The day's FIRST call's nudge (prediction minus its own baseline, % of baseline): did the
         * AI lean up or down at the open. */
        Double firstCallNudgePct,
        /** The most recent earlier call's nudge — how far, and which way, it chose to deviate from
         * "assume no change" the last time it spoke. */
        Double lastCallNudgePct,
        /** The most recent earlier call's move from the call before it, % — the size and direction
         * of its latest revision. Null until two earlier calls exist. */
        Double lastCallStepPct,
        /** The most recent earlier call's total drift from the day's first call, %. */
        Double lastCallDriftFromFirstPct
) {
    static final SameDayAiContext EMPTY = new SameDayAiContext(null, null, null, null);
}
