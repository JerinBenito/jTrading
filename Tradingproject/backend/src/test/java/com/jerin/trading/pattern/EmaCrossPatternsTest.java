package com.jerin.trading.pattern;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmaCrossPatternsTest {

    private final EmaBullishCrossPattern bullish = new EmaBullishCrossPattern();
    private final EmaBearishCrossPattern bearish = new EmaBearishCrossPattern();

    @Test
    void bullishFiresWhenShortCrossesAboveLong() {
        // prev: short(9) <= long(10); cur: short(11) > long(10)
        PatternContext ctx = context(list(9, 11), list(10, 10));

        assertThat(bullish.firesAt(1, ctx)).isTrue();
        assertThat(bearish.firesAt(1, ctx)).isFalse();
    }

    @Test
    void bullishDoesNotFireWithoutACross() {
        // short stays above long the whole time -> no cross
        PatternContext ctx = context(list(11, 12), list(10, 10));

        assertThat(bullish.firesAt(1, ctx)).isFalse();
    }

    @Test
    void bullishFiresOnExactTouchBoundary() {
        // prev short == prev long (touching, not yet above) counts as "at or below" per <=
        PatternContext ctx = context(list(10, 11), list(10, 10));

        assertThat(bullish.firesAt(1, ctx)).isTrue();
    }

    @Test
    void bearishFiresWhenShortCrossesBelowLong() {
        PatternContext ctx = context(list(11, 9), list(10, 10));

        assertThat(bearish.firesAt(1, ctx)).isTrue();
        assertThat(bullish.firesAt(1, ctx)).isFalse();
    }

    @Test
    void neitherFiresAtIndexZero() {
        PatternContext ctx = context(list(9, 11), list(10, 10));

        assertThat(bullish.firesAt(0, ctx)).isFalse();
        assertThat(bearish.firesAt(0, ctx)).isFalse();
    }

    @Test
    void neitherFiresWhenIndicatorsNotYetAvailable() {
        PatternContext ctx = context(nullable(null, 11), list(10, 10));

        assertThat(bullish.firesAt(1, ctx)).isFalse();
        assertThat(bearish.firesAt(1, ctx)).isFalse();
    }

    private static PatternContext context(List<BigDecimal> ema9, List<BigDecimal> ema21) {
        return new PatternContext(List.of(), ema9, ema21, List.of());
    }

    private static List<BigDecimal> list(double... values) {
        return Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }

    private static List<BigDecimal> nullable(Double first, double second) {
        return Arrays.asList(first == null ? null : BigDecimal.valueOf(first), BigDecimal.valueOf(second));
    }
}
