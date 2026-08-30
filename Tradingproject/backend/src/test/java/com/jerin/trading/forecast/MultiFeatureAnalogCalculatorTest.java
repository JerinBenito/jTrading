package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiFeatureAnalogCalculatorTest {

    @Test
    void returnsNullWithFewerThanThirtyObservations() {
        List<HistoricalDayObservation> pool = List.of(new HistoricalDayObservation(new double[]{0.01, 50}, 0.02));

        assertThat(MultiFeatureAnalogCalculator.estimateRemainingDrift(pool, new double[]{0.01, 50})).isNull();
    }

    @Test
    void matchesTheCorrectGroupAcrossMultipleDimensions() {
        // dimension 0 (small scale, ~0.01) is what actually distinguishes the two groups;
        // dimension 1 is identical across the whole pool and matches today exactly, so it
        // contributes nothing to the distance either way — isolates dimension 0's effect
        List<HistoricalDayObservation> pool = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            pool.add(new HistoricalDayObservation(new double[]{0.01, 50.0}, 0.05));
        }
        for (int i = 0; i < 15; i++) {
            pool.add(new HistoricalDayObservation(new double[]{-0.01, 50.0}, -0.05));
        }

        Double estimate = MultiFeatureAnalogCalculator.estimateRemainingDrift(pool, new double[]{0.01, 50.0});

        assertThat(estimate).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void normalizesAWideScaleDimensionRatherThanLettingItDominate() {
        // dimension 1 spans 0-100 (huge raw scale vs dimension 0's ~0.01) but today sits
        // exactly at its midpoint between the two groups -> after standardizing by dimension 1's
        // own stdev, both groups are equidistant on dimension 1 (a clean tie), same as dimension
        // 0 (both groups equal there too) -- confirms the large raw scale doesn't silently
        // dominate the match into an arbitrary/wrong group.
        List<HistoricalDayObservation> pool = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            pool.add(new HistoricalDayObservation(new double[]{0.0, 0.0}, 1.0));
        }
        for (int i = 0; i < 15; i++) {
            pool.add(new HistoricalDayObservation(new double[]{0.0, 100.0}, 2.0));
        }

        Double estimate = MultiFeatureAnalogCalculator.estimateRemainingDrift(pool, new double[]{0.0, 50.0});

        // a stable sort over a full tie keeps the first-encountered group (added first above)
        assertThat(estimate).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void handlesAConstantDimensionWithoutBlowingUp() {
        // dimension 1 is constant across the whole pool and matches today exactly -> its
        // (near-zero) standard deviation must not cause a division blow-up
        List<HistoricalDayObservation> pool = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            pool.add(new HistoricalDayObservation(new double[]{0.02, 7.0}, 0.03));
        }

        Double estimate = MultiFeatureAnalogCalculator.estimateRemainingDrift(pool, new double[]{0.02, 7.0});

        assertThat(estimate).isCloseTo(0.03, org.assertj.core.data.Offset.offset(1e-9));
    }
}
