package com.jerin.trading.indicator;

import com.jerin.trading.domain.OptionChainSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Put-Call Ratio by open interest, from a single option chain snapshot (all rows sharing one ts). */
public final class PcrCalculator {

    private PcrCalculator() {
    }

    public static BigDecimal calculate(List<OptionChainSnapshot> snapshotRows) {
        long callOi = sumOi(snapshotRows, "CE");
        long putOi = sumOi(snapshotRows, "PE");
        if (callOi == 0) {
            return null;
        }
        return BigDecimal.valueOf((double) putOi / callOi).setScale(4, RoundingMode.HALF_UP);
    }

    private static long sumOi(List<OptionChainSnapshot> rows, String optionType) {
        return rows.stream()
                .filter(r -> optionType.equals(r.getOptionType()))
                .mapToLong(r -> r.getOi() == null ? 0L : r.getOi())
                .sum();
    }
}
