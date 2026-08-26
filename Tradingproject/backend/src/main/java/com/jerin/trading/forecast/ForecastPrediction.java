package com.jerin.trading.forecast;

import java.math.BigDecimal;

/** A one-step-ahead prediction — never a bare price, always a range, matching the project's no-fabricated-precision rule. */
public record ForecastPrediction(BigDecimal predictedClose, BigDecimal rangeLow, BigDecimal rangeHigh) {
}
