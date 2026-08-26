package com.jerin.trading.indicator;

import com.jerin.trading.domain.OhlcvCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AtrCalculatorTest {

    @Test
    void seedValueIsSimpleAverageOfTrueRanges() {
        // TR for each bar (given constant high-low=2 and close=prevClose+1 each time) is exactly 2
        List<OhlcvCandle> candles = List.of(
                candle(10, 8, 9),
                candle(11, 9, 10),   // TR = max(2, |11-9|=2, |9-9|=0) = 2
                candle(12, 10, 11),  // TR = max(2, |12-10|=2, |10-10|=0) = 2
                candle(13, 11, 12)   // TR = max(2, |13-11|=2, |11-11|=0) = 2
        );
        List<BigDecimal> atr = AtrCalculator.calculate(candles, 3);

        assertThat(atr.subList(0, 3)).containsOnlyNulls();
        assertThat(atr.get(3)).isEqualByComparingTo("2.0000");
    }

    @Test
    void subsequentValueAppliesWildersSmoothing() {
        // 5th bar: TR = max(15-12=3, |15-12|=3, |12-12|=0) = 3
        // ATR = (2.0*2 + 3)/3 = 7/3 = 2.3333
        List<OhlcvCandle> candles = List.of(
                candle(10, 8, 9),
                candle(11, 9, 10),
                candle(12, 10, 11),
                candle(13, 11, 12),
                candle(15, 12, 14)
        );
        List<BigDecimal> atr = AtrCalculator.calculate(candles, 3);

        assertThat(atr.get(4)).isEqualByComparingTo("2.3333");
    }

    @Test
    void tooFewCandlesReturnsAllNulls() {
        List<OhlcvCandle> candles = List.of(candle(10, 8, 9), candle(11, 9, 10));
        List<BigDecimal> atr = AtrCalculator.calculate(candles, 5);

        assertThat(atr).hasSize(2).containsOnlyNulls();
    }

    private static OhlcvCandle candle(double high, double low, double close) {
        return OhlcvCandle.builder()
                .high(BigDecimal.valueOf(high))
                .low(BigDecimal.valueOf(low))
                .close(BigDecimal.valueOf(close))
                .build();
    }
}
