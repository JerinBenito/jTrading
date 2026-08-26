package com.jerin.trading.forecast;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Current live calibration state for one instrument+interval — what's actually being applied to new predictions right now. */
public record RangeCalibrationStatus(String instrument, String interval, BigDecimal multiplier, OffsetDateTime updatedAt) {
}
