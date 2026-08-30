package com.jerin.trading.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/** Shared quartile-bucketing logic used by both the single-instrument and pooled momentum backtests. */
final class MomentumQuartileBucketer {

    private static final String[] LABELS = {"Q1_WORST_PAST_RETURN", "Q2", "Q3", "Q4_BEST_PAST_RETURN"};

    private MomentumQuartileBucketer() {
    }

    static List<MomentumBucketResult> build(List<Double> pastReturns, List<Double> futureReturns) {
        int n = pastReturns.size();
        Integer[] sortedIndices = IntStream.range(0, n).boxed().toArray(Integer[]::new);
        Arrays.sort(sortedIndices, (a, b) -> Double.compare(pastReturns.get(a), pastReturns.get(b)));

        List<MomentumBucketResult> results = new ArrayList<>();
        int quartileSize = n / 4;

        for (int q = 0; q < 4; q++) {
            int start = q * quartileSize;
            int end = (q == 3) ? n : start + quartileSize;

            int count = 0;
            double sumPast = 0;
            double sumFuture = 0;
            int wins = 0;
            for (int idx = start; idx < end; idx++) {
                int i = sortedIndices[idx];
                sumPast += pastReturns.get(i);
                sumFuture += futureReturns.get(i);
                if (futureReturns.get(i) > 0) {
                    wins++;
                }
                count++;
            }

            results.add(new MomentumBucketResult(
                    LABELS[q], count,
                    BigDecimal.valueOf(sumPast / count * 100).setScale(3, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(sumFuture / count * 100).setScale(3, RoundingMode.HALF_UP),
                    BigDecimal.valueOf((double) wins / count * 100).setScale(2, RoundingMode.HALF_UP)));
        }
        return results;
    }
}
