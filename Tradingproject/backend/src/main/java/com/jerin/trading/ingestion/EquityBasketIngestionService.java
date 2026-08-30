package com.jerin.trading.ingestion;

import com.jerin.trading.broker.BrokerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Backfills hourly candles for individual NSE equities (e.g. the NIFTY 50 basket, see
 * {@link Nifty50Constituents}) under their own trading symbol as the instrument tag — purely to
 * grow the cross-sectional sample size for the Phase C momentum backtest ({@link
 * com.jerin.trading.forecast.MomentumBacktestService}). Each stock's own time dimension is still
 * only as deep as this backfill's date range; pooling across stocks increases the number of
 * independent series, not the per-series history length.
 */
@Service
public class EquityBasketIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EquityBasketIngestionService.class);
    private static final int MAX_HOURLY_WINDOW_DAYS = 89;

    private final BrokerClient brokerClient;
    private final IngestionService ingestionService;

    public EquityBasketIngestionService(BrokerClient brokerClient, IngestionService ingestionService) {
        this.brokerClient = brokerClient;
        this.ingestionService = ingestionService;
    }

    public EquityIngestionResult backfillSymbol(String symbol, String unit, int interval, LocalDate from, LocalDate to) {
        Optional<String> instrumentKey = brokerClient.findEquityInstrumentKey(symbol);
        if (instrumentKey.isEmpty()) {
            log.warn("No equity instrument_key found for {}", symbol);
            return new EquityIngestionResult(symbol, null, 0, "NOT_FOUND");
        }

        int windowDays = "hours".equals(unit) ? MAX_HOURLY_WINDOW_DAYS : Integer.MAX_VALUE;
        int totalSaved = 0;
        LocalDate chunkFrom = from;
        while (!chunkFrom.isAfter(to)) {
            LocalDate chunkTo = chunkFrom.plusDays(windowDays - 1);
            if (chunkTo.isAfter(to)) {
                chunkTo = to;
            }
            totalSaved += ingestionService.ingestCandlesForKey(symbol, instrumentKey.get(), unit, interval, chunkFrom, chunkTo);
            chunkFrom = chunkTo.plusDays(1);
        }

        return new EquityIngestionResult(symbol, instrumentKey.get(), totalSaved, "OK");
    }

    public List<EquityIngestionResult> backfillBasket(String unit, int interval, LocalDate from, LocalDate to) {
        return Nifty50Constituents.SYMBOLS.stream()
                .map(symbol -> backfillSymbol(symbol, unit, interval, from, to))
                .toList();
    }
}
