package com.jerin.trading.pattern;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class EmaBullishCrossPattern implements Pattern {

    @Override
    public String id() {
        return "EMA_BULLISH_CROSS";
    }

    @Override
    public PatternDirection direction() {
        return PatternDirection.UP;
    }

    @Override
    public boolean firesAt(int index, PatternContext context) {
        if (index < 1) {
            return false;
        }
        BigDecimal prevShort = context.ema9().get(index - 1);
        BigDecimal prevLong = context.ema21().get(index - 1);
        BigDecimal curShort = context.ema9().get(index);
        BigDecimal curLong = context.ema21().get(index);
        if (prevShort == null || prevLong == null || curShort == null || curLong == null) {
            return false;
        }
        return prevShort.compareTo(prevLong) <= 0 && curShort.compareTo(curLong) > 0;
    }
}
