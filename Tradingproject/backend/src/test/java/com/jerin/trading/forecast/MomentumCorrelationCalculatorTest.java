package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MomentumCorrelationCalculatorTest {

    @Test
    void perfectPositiveRelationshipGivesCorrelationOfOne() {
        List<Double> x = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> y = List.of(2.0, 4.0, 6.0, 8.0, 10.0);

        assertThat(MomentumCorrelationCalculator.pearsonCorrelation(x, y)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void perfectNegativeRelationshipGivesCorrelationOfMinusOne() {
        List<Double> x = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> y = List.of(10.0, 8.0, 6.0, 4.0, 2.0);

        assertThat(MomentumCorrelationCalculator.pearsonCorrelation(x, y)).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void noRelationshipGivesCorrelationCloseToZero() {
        List<Double> x = List.of(1.0, -1.0, 1.0, -1.0, 1.0, -1.0);
        List<Double> y = List.of(1.0, 1.0, -1.0, -1.0, 1.0, -1.0);

        assertThat(MomentumCorrelationCalculator.pearsonCorrelation(x, y)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void returnsZeroForEmptyInput() {
        assertThat(MomentumCorrelationCalculator.pearsonCorrelation(List.of(), List.of())).isEqualTo(0.0);
    }

    @Test
    void returnsZeroWhenOneSeriesHasNoVariance() {
        List<Double> x = List.of(5.0, 5.0, 5.0, 5.0);
        List<Double> y = List.of(1.0, 2.0, 3.0, 4.0);

        assertThat(MomentumCorrelationCalculator.pearsonCorrelation(x, y)).isEqualTo(0.0);
    }
}
