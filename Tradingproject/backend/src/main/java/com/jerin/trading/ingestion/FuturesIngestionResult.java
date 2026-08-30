package com.jerin.trading.ingestion;

public record FuturesIngestionResult(
        String instrumentTag,
        String contractTradingSymbol,
        String contractInstrumentKey,
        String contractExpiry,
        int candlesSaved
) {
}
