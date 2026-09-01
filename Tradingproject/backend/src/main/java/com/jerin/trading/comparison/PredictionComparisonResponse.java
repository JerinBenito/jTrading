package com.jerin.trading.comparison;

import java.time.LocalDate;
import java.util.List;

public record PredictionComparisonResponse(String instrument, LocalDate date, List<PredictionComparisonRow> rows) {
}
