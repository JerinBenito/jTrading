package com.jerin.trading.pattern;

/**
 * Patterns are hand-defined up front from established TA logic, not discovered from data —
 * discovering patterns by mining historical data would overfit (see project constraints).
 */
public interface Pattern {

    String id();

    PatternDirection direction();

    /** True if this pattern fires on the bar at {@code index} within {@code context}. */
    boolean firesAt(int index, PatternContext context);
}
