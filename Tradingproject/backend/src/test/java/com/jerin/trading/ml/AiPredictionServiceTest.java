package com.jerin.trading.ml;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPredictionServiceTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 9, 22);

    // --- withinRange: the learned-range coverage check ---

    @Test
    void withinRangeIsTrueOnOrInsideTheBoundsInclusive() {
        assertTrue(AiPredictionService.withinRange(bd("100"), bd("98"), bd("102")));
        assertTrue(AiPredictionService.withinRange(bd("98"), bd("98"), bd("102"))); // exactly on the low edge
        assertTrue(AiPredictionService.withinRange(bd("102"), bd("98"), bd("102"))); // exactly on the high edge
    }

    @Test
    void withinRangeIsFalseOutsideTheBounds() {
        assertFalse(AiPredictionService.withinRange(bd("97.99"), bd("98"), bd("102")));
        assertFalse(AiPredictionService.withinRange(bd("102.01"), bd("98"), bd("102")));
    }

    @Test
    void withinRangeIsNullNotFalseWhenNoRangeWasRecorded() {
        assertNull(AiPredictionService.withinRange(bd("100"), null, bd("102")));
        assertNull(AiPredictionService.withinRange(bd("100"), bd("98"), null));
        assertNull(AiPredictionService.withinRange(bd("100"), null, null));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // --- the intraday recording cutoff, reused by both the guard and the range/error scoring ---

    @Test
    void beforeTheCutoffIsNotPastIt() {
        assertFalse(AiPredictionService.isPastIntradayCutoff(istAt(10, 10), TARGET));
        assertFalse(AiPredictionService.isPastIntradayCutoff(istAt(15, 29), TARGET));
    }

    @Test
    void atOrAfterTheCutoffIsPastIt() {
        assertTrue(AiPredictionService.isPastIntradayCutoff(istAt(15, 31), TARGET));
        assertTrue(AiPredictionService.isPastIntradayCutoff(istAt(22, 0), TARGET));
    }

    @Test
    void aCallOnADifferentDayIsNeverPastItsOwnTargetDaysCutoff() {
        // the day AFTER the target: the target day's whole session, cutoff included, is behind it
        assertTrue(AiPredictionService.isPastIntradayCutoff(
                TARGET.plusDays(1).atTime(0, 30).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime(), TARGET));
    }

    private static OffsetDateTime istAt(int hour, int minute) {
        return TARGET.atTime(hour, minute).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
    }
}
