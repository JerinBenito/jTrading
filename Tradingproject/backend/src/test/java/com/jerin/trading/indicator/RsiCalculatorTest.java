package com.jerin.trading.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.DoubleStream;

import static org.assertj.core.api.Assertions.assertThat;

class RsiCalculatorTest {

    @Test
    void seedValueMatchesHandComputedWildersRsi() {
        // changes: +2, -1, +2 -> avgGain=4/3, avgLoss=1/3, RS=4, RSI=100-100/5=80
        List<BigDecimal> closes = closes(10, 12, 11, 13);
        List<BigDecimal> rsi = RsiCalculator.calculate(closes, 3);

        assertThat(rsi.subList(0, 3)).containsOnlyNulls();
        assertThat(rsi.get(3)).isEqualByComparingTo("80.0000");
    }

    @Test
    void subsequentValueAppliesWildersSmoothing() {
        // 5th close 12 -> change -1; avgGain=(4/3*2+0)/3=0.888..., avgLoss=(1/3*2+1)/3=0.555...
        // RS=1.6, RSI=100-100/2.6=61.5385
        List<BigDecimal> closes = closes(10, 12, 11, 13, 12);
        List<BigDecimal> rsi = RsiCalculator.calculate(closes, 3);

        assertThat(rsi.get(4)).isEqualByComparingTo("61.5385");
    }

    @Test
    void allGainsProducesMaxRsi() {
        List<BigDecimal> closes = closes(10, 11, 12, 13, 14, 15);
        List<BigDecimal> rsi = RsiCalculator.calculate(closes, 3);

        assertThat(rsi.get(3)).isEqualByComparingTo("100.0000");
        assertThat(rsi.get(5)).isEqualByComparingTo("100.0000");
    }

    @Test
    void allLossesProducesMinRsi() {
        List<BigDecimal> closes = closes(15, 14, 13, 12, 11, 10);
        List<BigDecimal> rsi = RsiCalculator.calculate(closes, 3);

        assertThat(rsi.get(3)).isEqualByComparingTo("0.0000");
        assertThat(rsi.get(5)).isEqualByComparingTo("0.0000");
    }

    @Test
    void tooFewClosesReturnsAllNulls() {
        List<BigDecimal> closes = closes(10, 11, 12);
        List<BigDecimal> rsi = RsiCalculator.calculate(closes, 5);

        assertThat(rsi).hasSize(3).containsOnlyNulls();
    }

    private static List<BigDecimal> closes(double... values) {
        return DoubleStream.of(values).mapToObj(BigDecimal::valueOf).toList();
    }
}
