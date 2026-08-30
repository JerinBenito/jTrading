package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalAnalogCalculatorTest {

    @Test
    void returnsNullWithFewerThanThirtyObservations() {
        List<ReturnDriftPair> pool = List.of(new ReturnDriftPair(0.01, 0.02));

        assertThat(HistoricalAnalogCalculator.estimateRemainingDrift(pool, 0.01)).isNull();
    }

    @Test
    void averagesTheNearestNeighborsRemainingDrift() {
        // 15 days that moved +1% by this hour and then drifted a further +0.5% to close,
        // plus 20 unrelated days far away in return-so-far (0.10 = 10%) that should be ignored
        List<ReturnDriftPair> pool = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            pool.add(new ReturnDriftPair(0.010, 0.005));
        }
        for (int i = 0; i < 20; i++) {
            pool.add(new ReturnDriftPair(0.10, -0.08));
        }

        Double estimate = HistoricalAnalogCalculator.estimateRemainingDrift(pool, 0.010);

        assertThat(estimate).isCloseTo(0.005, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void picksTheClosestKNeighborsByReturnSoFar() {
        // today moved +2%; nearest 15 neighbors (by |returnSoFar - 0.02|) should be the ones at 0.019-0.021,
        // not the 20 padding entries at 0.0 far away
        List<ReturnDriftPair> pool = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            pool.add(new ReturnDriftPair(0.019 + i * 0.0001, 0.03)); // all within [0.019, 0.0204], all drift 0.03
        }
        for (int i = 0; i < 20; i++) {
            pool.add(new ReturnDriftPair(0.0, -0.05));
        }

        Double estimate = HistoricalAnalogCalculator.estimateRemainingDrift(pool, 0.02);

        assertThat(estimate).isCloseTo(0.03, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void usesWhateverIsAvailableWhenPoolIsSmallerThanK() {
        List<ReturnDriftPair> pool = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            pool.add(new ReturnDriftPair(0.0, 0.01));
        }

        // pool has exactly MIN_POOL_SIZE (30), fewer than K_NEIGHBORS would be if larger — still works
        Double estimate = HistoricalAnalogCalculator.estimateRemainingDrift(pool, 0.0);

        assertThat(estimate).isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-9));
    }
}
