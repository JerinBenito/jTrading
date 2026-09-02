package com.jerin.trading.comparison;

/**
 * Head-to-head accuracy of the two live prediction tracks (DETERMINISTIC vs AI) over their most
 * recent shared, evaluated days for one instrument — "which one's actually been winning lately,"
 * computed from real recorded outcomes, never simulated. {@code sampleSize} is the number of
 * days where both models had an evaluated same-day prediction to compare; it grows slowly since
 * both ledgers are young — treat small sample sizes as not yet meaningful.
 */
public record ModelLeaderboard(
        String instrument,
        int sampleSize,
        int deterministicWins,
        int aiWins,
        int ties
) {
}
