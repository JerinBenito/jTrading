package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BiasCorrectionCalculatorTest {

    @Test
    void returnsZeroWithFewerThanThirtyObservations() {
        List<Double> errors = Collections.nCopies(29, 10.0);

        assertThat(BiasCorrectionCalculator.calculate(errors)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void appliesShrunkCorrectionWhenBiasIsStatisticallySignificant() {
        // 15x 9.0, 15x 11.0 -> mean=10.0, sample stddev~1.017, SE~0.186, threshold~0.279
        // mean (10.0) is far beyond the significance threshold -> correction = 0.5 * 10.0 = 5.0
        List<Double> errors = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            errors.add(9.0);
            errors.add(11.0);
        }

        assertThat(BiasCorrectionCalculator.calculate(errors)).isEqualByComparingTo("5.0000");
    }

    @Test
    void returnsZeroWhenMeanIsNotDistinguishableFromNoise() {
        // 15x +5.0, 15x -5.0 -> mean=0.0 exactly, high variance -> nowhere near significant
        List<Double> errors = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            errors.add(5.0);
            errors.add(-5.0);
        }

        assertThat(BiasCorrectionCalculator.calculate(errors)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void returnsZeroWhenAllValuesIdentical() {
        // zero variance -> standard error is zero -> guarded explicitly rather than dividing by zero
        List<Double> errors = Collections.nCopies(30, 7.0);

        assertThat(BiasCorrectionCalculator.calculate(errors)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
