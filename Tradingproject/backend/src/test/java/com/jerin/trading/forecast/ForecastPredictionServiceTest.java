package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastPredictionServiceTest {

    // Dependencies unused by isLikelyTradingHour, so null is fine here.
    private final ForecastPredictionService service = new ForecastPredictionService(null, null, null, null);

    @Test
    void endOfDayCandlePlusOneHourIsOutsideTradingHours() {
        // 2026-08-14 is a Friday; 09:45Z = 15:15 IST (last candle of the day); +1h -> 16:15 IST, after close
        OffsetDateTime afterClose = OffsetDateTime.parse("2026-08-14T10:45:00Z");

        assertThat(service.isLikelyTradingHour(afterClose)).isFalse();
    }

    @Test
    void midDayHourIsWithinTradingHours() {
        // 05:45Z = 11:15 IST, well within market hours
        OffsetDateTime midDay = OffsetDateTime.parse("2026-08-14T05:45:00Z");

        assertThat(service.isLikelyTradingHour(midDay)).isTrue();
    }

    @Test
    void weekendIsNeverATradingHour() {
        // 2026-08-15 is a Saturday, even at a normally-valid hour
        OffsetDateTime saturday = OffsetDateTime.parse("2026-08-15T05:45:00Z");

        assertThat(service.isLikelyTradingHour(saturday)).isFalse();
    }
}
