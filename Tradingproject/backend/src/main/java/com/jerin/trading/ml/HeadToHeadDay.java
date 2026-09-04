package com.jerin.trading.ml;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day where both the DETERMINISTIC and AI same-day-close predictions were evaluated —
 * the raw material for both {@link ModelHeadToHeadService} consumers: the model leaderboard
 * and {@link AdaptiveSelectionService}. */
public record HeadToHeadDay(LocalDate date, BigDecimal deterministicError, BigDecimal aiError) {

    public boolean aiWon() {
        return aiError.compareTo(deterministicError) < 0;
    }

    public boolean deterministicWon() {
        return deterministicError.compareTo(aiError) < 0;
    }
}
