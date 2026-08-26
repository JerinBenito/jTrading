package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyForecastModelsTest {

    private final DailyRandomWalkModel randomWalk = new DailyRandomWalkModel();
    private final DailyMomentumModel momentum = new DailyMomentumModel();

    @Test
    void randomWalkPredictsTodaysOpenWithYesterdaysAtrRange() {
        // index 1 = today; only its open is used, yesterday's ATR (index 0) is the range
        ForecastContext ctx = new ForecastContext(
                List.of(candle(23900.0, 24000.0), candle(24100.0, 24300.0)),
                Arrays.asList((BigDecimal) null, null),
                Arrays.asList((BigDecimal) null, null),
                Arrays.asList(BigDecimal.valueOf(50), BigDecimal.valueOf(999)));

        ForecastPrediction prediction = randomWalk.predictClose(1, ctx);

        assertThat(prediction.predictedClose()).isEqualByComparingTo("24100.0");
        assertThat(prediction.rangeLow()).isEqualByComparingTo("24050.0");
        assertThat(prediction.rangeHigh()).isEqualByComparingTo("24150.0");
    }

    @Test
    void randomWalkReturnsNullOnFirstDay() {
        ForecastContext ctx = new ForecastContext(
                List.of(candle(23900.0, 24000.0)),
                Arrays.asList((BigDecimal) null),
                Arrays.asList((BigDecimal) null),
                Arrays.asList(BigDecimal.valueOf(50)));

        assertThat(randomWalk.predictClose(0, ctx)).isNull();
    }

    @Test
    void randomWalkReturnsNullWithoutPriorAtr() {
        ForecastContext ctx = new ForecastContext(
                List.of(candle(23900.0, 24000.0), candle(24100.0, 24300.0)),
                Arrays.asList((BigDecimal) null, null),
                Arrays.asList((BigDecimal) null, null),
                Arrays.asList((BigDecimal) null, BigDecimal.valueOf(60)));

        assertThat(randomWalk.predictClose(1, ctx)).isNull();
    }

    @Test
    void momentumAddsDampedYesterdaysEmaSpreadToTodaysOpen() {
        // yesterday's ema9=24100, ema21=24000 -> spread=100 * 0.25 dampening = 25 drift on today's open (24100)
        ForecastContext ctx = new ForecastContext(
                List.of(candle(23900.0, 24000.0), candle(24100.0, 24300.0)),
                Arrays.asList(BigDecimal.valueOf(24100.0), null),
                Arrays.asList(BigDecimal.valueOf(24000.0), null),
                Arrays.asList(BigDecimal.valueOf(50), null));

        ForecastPrediction prediction = momentum.predictClose(1, ctx);

        assertThat(prediction.predictedClose()).isEqualByComparingTo("24125.0000");
        assertThat(prediction.rangeLow()).isEqualByComparingTo("24075.0000");
        assertThat(prediction.rangeHigh()).isEqualByComparingTo("24175.0000");
    }

    @Test
    void momentumReturnsNullWithoutPriorEmas() {
        ForecastContext ctx = new ForecastContext(
                List.of(candle(23900.0, 24000.0), candle(24100.0, 24300.0)),
                Arrays.asList((BigDecimal) null, null),
                Arrays.asList((BigDecimal) null, null),
                Arrays.asList(BigDecimal.valueOf(50), null));

        assertThat(momentum.predictClose(1, ctx)).isNull();
    }

    private static OhlcvCandle candle(double open, double close) {
        return OhlcvCandle.builder()
                .open(BigDecimal.valueOf(open))
                .high(BigDecimal.valueOf(Math.max(open, close)))
                .low(BigDecimal.valueOf(Math.min(open, close)))
                .close(BigDecimal.valueOf(close))
                .build();
    }
}
