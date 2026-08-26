package com.jerin.trading.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Wilder's RSI. Needs period+1 closes for the first value (the seed average needs
 * `period` price changes). Entries before that are null.
 */
public final class RsiCalculator {

    private RsiCalculator() {
    }

    public static List<BigDecimal> calculate(List<BigDecimal> closes, int period) {
        List<BigDecimal> result = new ArrayList<>(closes.size());
        if (closes.size() < period + 1) {
            closes.forEach(c -> result.add(null));
            return result;
        }

        result.add(null);
        double avgGain = 0;
        double avgLoss = 0;

        for (int i = 1; i <= period; i++) {
            double change = closes.get(i).doubleValue() - closes.get(i - 1).doubleValue();
            avgGain += Math.max(change, 0);
            avgLoss += Math.max(-change, 0);
            result.add(i < period ? null : null);
        }
        avgGain /= period;
        avgLoss /= period;
        result.set(period, rsiFrom(avgGain, avgLoss));

        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i).doubleValue() - closes.get(i - 1).doubleValue();
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            result.add(rsiFrom(avgGain, avgLoss));
        }
        return result;
    }

    private static BigDecimal rsiFrom(double avgGain, double avgLoss) {
        if (avgLoss == 0) {
            return BigDecimal.valueOf(100).setScale(4, RoundingMode.HALF_UP);
        }
        double rs = avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));
        return BigDecimal.valueOf(rsi).setScale(4, RoundingMode.HALF_UP);
    }
}
