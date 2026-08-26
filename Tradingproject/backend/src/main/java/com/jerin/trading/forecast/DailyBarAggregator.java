package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates hourly candles into one daily bar per IST calendar day — reuses the hourly data
 * already ingested rather than pulling a separate daily series from the broker. A daily bar's
 * `ts` is deliberately set to its *first* hourly candle's ts (the day's open), so it lines up
 * exactly with the timestamp a live same-day prediction is recorded against.
 */
public final class DailyBarAggregator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private DailyBarAggregator() {
    }

    /** @param hourlyCandles must be ordered oldest to newest (same convention as everywhere else in this package) */
    public static List<OhlcvCandle> aggregate(List<OhlcvCandle> hourlyCandles) {
        List<OhlcvCandle> daily = new ArrayList<>();
        for (List<OhlcvCandle> day : groupByDay(hourlyCandles)) {
            OhlcvCandle first = day.get(0);
            OhlcvCandle current = OhlcvCandle.builder()
                    .instrument(first.getInstrument())
                    .interval("1d")
                    .ts(first.getTs())
                    .open(first.getOpen())
                    .high(first.getHigh())
                    .low(first.getLow())
                    .close(first.getClose())
                    .volume(first.getVolume())
                    .build();
            for (int i = 1; i < day.size(); i++) {
                OhlcvCandle candle = day.get(i);
                current.setHigh(current.getHigh().max(candle.getHigh()));
                current.setLow(current.getLow().min(candle.getLow()));
                current.setClose(candle.getClose());
                if (current.getVolume() != null && candle.getVolume() != null) {
                    current.setVolume(current.getVolume() + candle.getVolume());
                }
            }
            daily.add(current);
        }
        return daily;
    }

    /** Groups hourly candles into one list per IST calendar day, preserving order — the raw building block behind {@link #aggregate}. */
    public static List<List<OhlcvCandle>> groupByDay(List<OhlcvCandle> hourlyCandles) {
        List<List<OhlcvCandle>> days = new ArrayList<>();
        List<OhlcvCandle> current = null;
        LocalDate currentDate = null;

        for (OhlcvCandle candle : hourlyCandles) {
            LocalDate date = candle.getTs().atZoneSameInstant(IST).toLocalDate();
            if (!date.equals(currentDate)) {
                current = new ArrayList<>();
                days.add(current);
                currentDate = date;
            }
            current.add(candle);
        }
        return days;
    }
}
