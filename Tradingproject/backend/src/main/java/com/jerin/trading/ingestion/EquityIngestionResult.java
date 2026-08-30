package com.jerin.trading.ingestion;

public record EquityIngestionResult(
        String symbol,
        String instrumentKey,
        int candlesSaved,
        String status
) {
}
