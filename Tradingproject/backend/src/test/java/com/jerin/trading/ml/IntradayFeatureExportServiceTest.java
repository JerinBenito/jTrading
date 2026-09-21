package com.jerin.trading.ml;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntradayFeatureExportServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

    private static ZonedDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(IST);
    }

    @Test
    void todayIsInProgressDuringAndJustAfterTheSession() {
        assertTrue(IntradayFeatureExportService.isTradingDayInProgress(MONDAY, at(MONDAY, 10, 10)));
        assertTrue(IntradayFeatureExportService.isTradingDayInProgress(MONDAY, at(MONDAY, 15, 10)));
        // the closing candle isn't ingested until the 15:45 catch-up, so 15:59 is still unfinished
        assertTrue(IntradayFeatureExportService.isTradingDayInProgress(MONDAY, at(MONDAY, 15, 59)));
    }

    @Test
    void todayIsCompleteOnceTheCloseHasBeenIngested() {
        assertFalse(IntradayFeatureExportService.isTradingDayInProgress(MONDAY, at(MONDAY, 16, 0)));
        assertFalse(IntradayFeatureExportService.isTradingDayInProgress(MONDAY, at(MONDAY, 22, 5)));
    }

    @Test
    void pastDaysAreAlwaysComplete() {
        assertFalse(IntradayFeatureExportService.isTradingDayInProgress(MONDAY.minusDays(3), at(MONDAY, 11, 0)));
        assertFalse(IntradayFeatureExportService.isTradingDayInProgress(MONDAY.minusDays(1), at(MONDAY, 9, 0)));
    }

    @Test
    void theDayBeforeTheMorningOfATradingDayIsNotAffected() {
        // 00:30 on Tuesday: Monday is over, and Tuesday has no candles yet
        LocalDate tuesday = MONDAY.plusDays(1);
        assertFalse(IntradayFeatureExportService.isTradingDayInProgress(MONDAY, at(tuesday, 0, 30)));
    }
}
