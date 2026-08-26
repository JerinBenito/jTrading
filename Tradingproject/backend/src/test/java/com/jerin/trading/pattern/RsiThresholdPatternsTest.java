package com.jerin.trading.pattern;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RsiThresholdPatternsTest {

    private final RsiOversoldReversalPattern oversold = new RsiOversoldReversalPattern();
    private final RsiOverboughtReversalPattern overbought = new RsiOverboughtReversalPattern();

    @Test
    void oversoldFiresBelowThirty() {
        assertThat(oversold.firesAt(0, contextWithRsi(29.99))).isTrue();
    }

    @Test
    void oversoldDoesNotFireAtExactlyThirty() {
        assertThat(oversold.firesAt(0, contextWithRsi(30.00))).isFalse();
    }

    @Test
    void overboughtFiresAboveSeventy() {
        assertThat(overbought.firesAt(0, contextWithRsi(70.01))).isTrue();
    }

    @Test
    void overboughtDoesNotFireAtExactlySeventy() {
        assertThat(overbought.firesAt(0, contextWithRsi(70.00))).isFalse();
    }

    @Test
    void neitherFiresInNeutralZone() {
        PatternContext ctx = contextWithRsi(50.0);

        assertThat(oversold.firesAt(0, ctx)).isFalse();
        assertThat(overbought.firesAt(0, ctx)).isFalse();
    }

    @Test
    void neitherFiresWhenRsiNotYetAvailable() {
        PatternContext ctx = new PatternContext(List.of(), List.of(), List.of(), Arrays.asList((BigDecimal) null));

        assertThat(oversold.firesAt(0, ctx)).isFalse();
        assertThat(overbought.firesAt(0, ctx)).isFalse();
    }

    private static PatternContext contextWithRsi(double value) {
        return new PatternContext(List.of(), List.of(), List.of(), List.of(BigDecimal.valueOf(value)));
    }
}
