package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyBarAggregatorTest {

    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    @Test
    void aggregatesMultipleHourlyCandlesIntoOneBarPerIstDay() {
        List<OhlcvCandle> hourly = List.of(
                candle("2026-08-24T09:15", 100, 105, 98, 102, 1000),
                candle("2026-08-24T10:15", 102, 110, 101, 108, 1500),
                candle("2026-08-24T11:15", 108, 109, 103, 104, 1200),
                candle("2026-08-25T09:15", 105, 106, 104, 106, 900));

        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);

        assertThat(daily).hasSize(2);

        OhlcvCandle day1 = daily.get(0);
        assertThat(day1.getTs()).isEqualTo(ts("2026-08-24T09:15"));
        assertThat(day1.getOpen()).isEqualByComparingTo("100");
        assertThat(day1.getHigh()).isEqualByComparingTo("110");
        assertThat(day1.getLow()).isEqualByComparingTo("98");
        assertThat(day1.getClose()).isEqualByComparingTo("104");
        assertThat(day1.getVolume()).isEqualTo(3700L);

        OhlcvCandle day2 = daily.get(1);
        assertThat(day2.getTs()).isEqualTo(ts("2026-08-25T09:15"));
        assertThat(day2.getOpen()).isEqualByComparingTo("105");
        assertThat(day2.getClose()).isEqualByComparingTo("106");
        assertThat(day2.getVolume()).isEqualTo(900L);
    }

    @Test
    void handlesSingleCandleDay() {
        List<OhlcvCandle> hourly = List.of(candle("2026-08-24T09:15", 100, 105, 98, 102, 1000));

        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);

        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).getOpen()).isEqualByComparingTo("100");
        assertThat(daily.get(0).getClose()).isEqualByComparingTo("102");
    }

    @Test
    void returnsEmptyListForEmptyInput() {
        assertThat(DailyBarAggregator.aggregate(List.of())).isEmpty();
    }

    private static OhlcvCandle candle(String ts, double open, double high, double low, double close, long volume) {
        return OhlcvCandle.builder()
                .instrument("NIFTY")
                .interval("1h")
                .ts(ts(ts))
                .open(BigDecimal.valueOf(open))
                .high(BigDecimal.valueOf(high))
                .low(BigDecimal.valueOf(low))
                .close(BigDecimal.valueOf(close))
                .volume(volume)
                .build();
    }

    private static OffsetDateTime ts(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + ":00" + IST_OFFSET);
    }
}
