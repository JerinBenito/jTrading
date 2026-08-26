package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RangeCalibratorTest {

    @Test
    void missWidensTheMultiplier() {
        double next = RangeCalibrator.update(1.0, false);

        assertThat(next).isGreaterThan(1.0);
    }

    @Test
    void hitNarrowsTheMultiplierSlightly() {
        double next = RangeCalibrator.update(1.0, true);

        assertThat(next).isLessThan(1.0);
    }

    @Test
    void missMovesFurtherThanHit() {
        double afterMiss = RangeCalibrator.update(1.0, false) - 1.0;
        double afterHit = 1.0 - RangeCalibrator.update(1.0, true);

        // at the 90% target, a miss should push harder than a hit pulls back (9x, matching the 0.9/0.1 asymmetry)
        assertThat(afterMiss).isGreaterThan(afterHit);
    }

    @Test
    void neverGoesBelowMinMultiplierEvenAfterManyHits() {
        double k = 1.0;
        for (int i = 0; i < 1000; i++) {
            k = RangeCalibrator.update(k, true);
        }

        assertThat(k).isEqualTo(RangeCalibrator.MIN_MULTIPLIER);
    }

    @Test
    void neverExceedsMaxMultiplierEvenAfterManyMisses() {
        double k = 1.0;
        for (int i = 0; i < 1000; i++) {
            k = RangeCalibrator.update(k, false);
        }

        assertThat(k).isEqualTo(RangeCalibrator.MAX_MULTIPLIER);
    }

    @Test
    void convergesTowardMultiplierThatMatchesAnAssumedTrueMissRate() {
        // simulate a stream where the true miss rate at multiplier ~1.0 is exactly 10% (matching the 90% target) —
        // the multiplier should stay stable in a tight band around 1.0, not drift away
        double k = 1.0;
        for (int i = 0; i < 500; i++) {
            boolean covered = i % 10 != 0; // 90% covered, 10% missed
            k = RangeCalibrator.update(k, covered);
        }

        assertThat(k).isBetween(0.8, 1.2);
    }
}
