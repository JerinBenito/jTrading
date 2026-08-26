package com.jerin.trading.indicator;

import com.jerin.trading.domain.OhlcvCandle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Wilder's ATR. Candles must be ordered oldest to newest. First `period` entries are
 * null — true range needs a previous close, and the seed ATR needs `period` true ranges.
 */
public final class AtrCalculator {

    private AtrCalculator() {
    }

    public static List<BigDecimal> calculate(List<OhlcvCandle> candles, int period) {
        List<BigDecimal> result = new ArrayList<>(candles.size());
        if (candles.size() < period + 1) {
            candles.forEach(c -> result.add(null));
            return result;
        }

        result.add(null);
        double atrPrev = 0;

        for (int i = 1; i <= period; i++) {
            atrPrev += trueRange(candles.get(i), candles.get(i - 1));
            result.add(null);
        }
        atrPrev /= period;
        result.set(period, round(atrPrev));

        for (int i = period + 1; i < candles.size(); i++) {
            double tr = trueRange(candles.get(i), candles.get(i - 1));
            atrPrev = (atrPrev * (period - 1) + tr) / period;
            result.add(round(atrPrev));
        }
        return result;
    }

    private static double trueRange(OhlcvCandle current, OhlcvCandle previous) {
        double high = current.getHigh().doubleValue();
        double low = current.getLow().doubleValue();
        double prevClose = previous.getClose().doubleValue();
        return Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
