package com.jerin.trading.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Exponential moving average. Output is aligned with the input: entries before the
 * first full period are null (not enough data yet), matching the no-fabricated-precision
 * constraint — we don't emit a value we can't actually support.
 */
public final class EmaCalculator {

    private EmaCalculator() {
    }

    public static List<BigDecimal> calculate(List<BigDecimal> closes, int period) {
        List<BigDecimal> result = new ArrayList<>(closes.size());
        if (closes.size() < period) {
            closes.forEach(c -> result.add(null));
            return result;
        }

        double multiplier = 2.0 / (period + 1);
        double emaPrev = 0;

        for (int i = 0; i < closes.size(); i++) {
            if (i < period - 1) {
                result.add(null);
            } else if (i == period - 1) {
                double sma = closes.subList(0, period).stream()
                        .mapToDouble(BigDecimal::doubleValue)
                        .average()
                        .orElseThrow();
                emaPrev = sma;
                result.add(round(sma));
            } else {
                double close = closes.get(i).doubleValue();
                emaPrev = (close - emaPrev) * multiplier + emaPrev;
                result.add(round(emaPrev));
            }
        }
        return result;
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
