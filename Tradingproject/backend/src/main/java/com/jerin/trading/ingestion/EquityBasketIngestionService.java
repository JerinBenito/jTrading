package com.jerin.trading.ingestion;

import com.jerin.trading.broker.BrokerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backfills and live-ingests hourly candles for individual NSE equities (the NIFTY 50 basket,
 * see {@link Nifty50Constituents}) under their own trading symbol as the instrument tag.
 * Originally built purely to grow the cross-sectional sample size for the Phase C momentum
 * backtest ({@link com.jerin.trading.forecast.MomentumBacktestService}); Phase D reuses the same
 * per-symbol instrument-key resolution to keep the basket's data live for monitoring (see
 * {@link com.jerin.trading.monitor.BasketMonitorService}) rather than a one-time historical dump.
 * Each stock's own time dimension is still only as deep as its backfill's date range; pooling
 * across stocks increases the number of independent series, not the per-series history length.
 */
@Service
public class EquityBasketIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EquityBasketIngestionService.class);
    private static final int MAX_HOURLY_WINDOW_DAYS = 89;

    private final BrokerClient brokerClient;
    private final IngestionService ingestionService;

    /** Equity instrument keys and ISINs are stable, so resolve each symbol at most once per
     * process lifetime rather than hitting the search endpoint 49 times every single hour. */
    private final Map<String, String> instrumentKeyCache = new ConcurrentHashMap<>();
    private final Map<String, String> isinCache = new ConcurrentHashMap<>();

    public EquityBasketIngestionService(BrokerClient brokerClient, IngestionService ingestionService) {
        this.brokerClient = brokerClient;
        this.ingestionService = ingestionService;
    }

    /** Public wrapper for consumers outside this service (e.g. the live feed relay) that need a
     * basket stock's actual Upstox instrument_key, not just its own trading-symbol tag. */
    public Optional<String> findInstrumentKey(String symbol) {
        return resolveInstrumentKey(symbol);
    }

    /** Public wrapper for consumers (e.g. {@link com.jerin.trading.fundamentals.CompanyFundamentalService})
     * that need a basket stock's ISIN — the fundamentals endpoints are keyed by ISIN, not instrument_key. */
    public Optional<String> findIsin(String symbol) {
        return resolveIsin(symbol);
    }

    private Optional<String> resolveInstrumentKey(String symbol) {
        String cached = instrumentKeyCache.get(symbol);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> resolved = brokerClient.findEquityInstrumentKey(symbol);
        resolved.ifPresent(key -> instrumentKeyCache.put(symbol, key));
        return resolved;
    }

    private Optional<String> resolveIsin(String symbol) {
        String cached = isinCache.get(symbol);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> resolved = brokerClient.findEquityIsin(symbol);
        resolved.ifPresent(isin -> isinCache.put(symbol, isin));
        return resolved;
    }

    public EquityIngestionResult backfillSymbol(String symbol, String unit, int interval, LocalDate from, LocalDate to) {
        Optional<String> instrumentKey = resolveInstrumentKey(symbol);
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

    /** Today's live/forming hourly candle for one basket symbol — what the hourly job calls. */
    public EquityIngestionResult ingestTodayForSymbol(String symbol) {
        Optional<String> instrumentKey = resolveInstrumentKey(symbol);
        if (instrumentKey.isEmpty()) {
            return new EquityIngestionResult(symbol, null, 0, "NOT_FOUND");
        }
        int saved = ingestionService.ingestIntradayCandlesForKey(symbol, instrumentKey.get(), "hours", 1);
        return new EquityIngestionResult(symbol, instrumentKey.get(), saved, "OK");
    }

    /** Today's live/forming hourly candles for every basket symbol — called once per hourly cycle. */
    public List<EquityIngestionResult> ingestTodayForBasket() {
        return Nifty50Constituents.SYMBOLS.stream()
                .map(this::ingestTodayForSymbol)
                .toList();
    }
}
