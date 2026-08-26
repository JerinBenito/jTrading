package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastModelsTest {

    private final RandomWalkForecastModel randomWalk = new RandomWalkForecastModel();
    private final MomentumForecastModel momentum = new MomentumForecastModel();

    @Test
    void randomWalkPredictsLastCloseWithAtrRange() {
        ForecastContext ctx = context(24000.0, null, BigDecimal.valueOf(50));

        ForecastPrediction prediction = randomWalk.predictNext(0, ctx);

        assertThat(prediction.predictedClose()).isEqualByComparingTo("24000.0");
        assertThat(prediction.rangeLow()).isEqualByComparingTo("23950.0");
        assertThat(prediction.rangeHigh()).isEqualByComparingTo("24050.0");
    }

    @Test
    void randomWalkReturnsNullWithoutAtr() {
        ForecastContext ctx = context(24000.0, null, null);

        assertThat(randomWalk.predictNext(0, ctx)).isNull();
    }

    @Test
    void momentumAddsDampedEmaSpreadAsDrift() {
        // ema9=24100, ema21=24000 -> spread=100 * 0.25 dampening = 25 drift
        ForecastContext ctx = new ForecastContext(
                List.of(candle(24000.0)),
                List.of(BigDecimal.valueOf(24100.0)),
                List.of(BigDecimal.valueOf(24000.0)),
                List.of(BigDecimal.valueOf(50)));

        ForecastPrediction prediction = momentum.predictNext(0, ctx);

        assertThat(prediction.predictedClose()).isEqualByComparingTo("24025.0000");
        assertThat(prediction.rangeLow()).isEqualByComparingTo("23975.0000");
        assertThat(prediction.rangeHigh()).isEqualByComparingTo("24075.0000");
    }

    @Test
    void momentumReturnsNullWithoutEmas() {
        ForecastContext ctx = context(24000.0, null, BigDecimal.valueOf(50));

        assertThat(momentum.predictNext(0, ctx)).isNull();
    }

    private static ForecastContext context(double close, BigDecimal ema, BigDecimal atr) {
        return new ForecastContext(
                List.of(candle(close)),
                Arrays.asList(ema),
                Arrays.asList(ema),
                Arrays.asList(atr));
    }

    private static com.jerin.trading.domain.OhlcvCandle candle(double close) {
        return com.jerin.trading.domain.OhlcvCandle.builder().close(BigDecimal.valueOf(close)).build();
    }
}
