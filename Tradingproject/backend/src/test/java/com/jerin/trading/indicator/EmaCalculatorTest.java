package com.jerin.trading.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmaCalculatorTest {

    @Test
    void firstPeriodMinusOneValuesAreNull() {
        List<BigDecimal> closes = closes(1, 2, 3, 4, 5);
        List<BigDecimal> ema = EmaCalculator.calculate(closes, 5);

        assertThat(ema.subList(0, 4)).containsOnlyNulls();
    }

    @Test
    void seedValueIsSimpleAverageOfFirstPeriod() {
        // classic textbook series, period 10 — seed EMA should equal the SMA of the first 10 closes
        List<BigDecimal> closes = closes(22.27, 22.19, 22.08, 22.17, 22.18, 22.13, 22.23, 22.43, 22.24, 22.29);
        List<BigDecimal> ema = EmaCalculator.calculate(closes, 10);

        assertThat(ema.get(9)).isEqualByComparingTo("22.221");
    }

    @Test
    void subsequentValueFollowsStandardEmaFormula() {
        // day 11 close = 22.15; multiplier = 2/11; expected = (22.15-22.221)*2/11 + 22.221
        List<BigDecimal> closes = closes(
                22.27, 22.19, 22.08, 22.17, 22.18, 22.13, 22.23, 22.43, 22.24, 22.29, 22.15);
        List<BigDecimal> ema = EmaCalculator.calculate(closes, 10);

        assertThat(ema.get(10)).isEqualByComparingTo("22.2081");
    }

    @Test
    void tooFewClosesReturnsAllNulls() {
        List<BigDecimal> closes = closes(1, 2, 3);
        List<BigDecimal> ema = EmaCalculator.calculate(closes, 5);

        assertThat(ema).hasSize(3).containsOnlyNulls();
    }

    private static List<BigDecimal> closes(double... values) {
        return java.util.stream.DoubleStream.of(values).mapToObj(BigDecimal::valueOf).toList();
    }
}
