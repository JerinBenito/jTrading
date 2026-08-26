package com.jerin.trading.pattern;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class RsiOversoldReversalPattern implements Pattern {

    private static final BigDecimal THRESHOLD = BigDecimal.valueOf(30);

    @Override
    public String id() {
        return "RSI_OVERSOLD_REVERSAL";
    }

    @Override
    public PatternDirection direction() {
        return PatternDirection.UP;
    }

    @Override
    public boolean firesAt(int index, PatternContext context) {
        BigDecimal rsi = context.rsi14().get(index);
        return rsi != null && rsi.compareTo(THRESHOLD) < 0;
    }
}
