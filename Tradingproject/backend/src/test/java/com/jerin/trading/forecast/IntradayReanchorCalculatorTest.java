package com.jerin.trading.forecast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntradayReanchorCalculatorTest {

    @Test
    void remainingFractionIsOneAtSessionStart() {
        assertThat(IntradayReanchorCalculator.remainingFraction(0, 360)).isEqualTo(1.0);
    }

    @Test
    void remainingFractionIsZeroAtSessionEnd() {
        assertThat(IntradayReanchorCalculator.remainingFraction(360, 360)).isEqualTo(0.0);
    }

    @Test
    void remainingFractionIsHalfwayAtMidSession() {
        assertThat(IntradayReanchorCalculator.remainingFraction(180, 360)).isEqualTo(0.5);
    }

    @Test
    void remainingFractionClampsElapsedBeyondSessionLength() {
        // shouldn't happen in practice, but must not go negative
        assertThat(IntradayReanchorCalculator.remainingFraction(400, 360)).isEqualTo(0.0);
    }

    @Test
    void remainingRangeWidthEqualsFullAtrAtSessionStart() {
        assertThat(IntradayReanchorCalculator.remainingRangeWidth(100.0, 1.0)).isEqualTo(100.0);
    }

    @Test
    void remainingRangeWidthShrinksBySquareRootOfRemainingFraction() {
        // remaining=0.25 -> sqrt(0.25)=0.5 -> half the full-day ATR width
        assertThat(IntradayReanchorCalculator.remainingRangeWidth(100.0, 0.25)).isEqualTo(50.0);
    }

    @Test
    void remainingRangeWidthNeverCollapsesToZero() {
        double width = IntradayReanchorCalculator.remainingRangeWidth(100.0, 0.0);

        assertThat(width).isEqualTo(100.0 * Math.sqrt(IntradayReanchorCalculator.MIN_REMAINING_FRACTION));
        assertThat(width).isGreaterThan(0.0);
    }
}
