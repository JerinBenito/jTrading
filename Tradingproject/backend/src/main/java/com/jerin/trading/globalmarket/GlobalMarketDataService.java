package com.jerin.trading.globalmarket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches overnight global-market context (US indices, crude oil, USD/INR) once daily, shortly
 * before Indian market open — Yahoo Finance's public chart endpoint, no API key required, same
 * one used by countless free tools for this exact purpose. Purely observational: this service
 * only records what happened, it doesn't feed into any prediction yet. See
 * {@link GlobalMarketSnapshot} for why.
 */
@Service
public class GlobalMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(GlobalMarketDataService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Raw Yahoo ticker per symbol tag — pass unencoded ("^GSPC", not "%5EGSPC"); the URI
     * template variable below encodes it exactly once. Pre-encoding it here would make
     * RestClient double-encode the "%" into "%25", corrupting the ticker. */
    private static final Map<String, String> TICKERS = new LinkedHashMap<>();

    static {
        TICKERS.put("SP500", "^GSPC");
        TICKERS.put("DOW", "^DJI");
        TICKERS.put("NASDAQ", "^IXIC");
        TICKERS.put("CRUDE_OIL", "CL=F");
        TICKERS.put("USD_INR", "INR=X");
    }

    private final RestClient restClient;
    private final GlobalMarketSnapshotRepository repository;

    public GlobalMarketDataService(GlobalMarketSnapshotRepository repository) {
        this.restClient = RestClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
        this.repository = repository;
    }

    /** Idempotent per (symbol, day) — safe to call more than once, e.g. from both the scheduled
     * trigger and a manual retry. */
    public int fetchToday() {
        LocalDate today = OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();
        int saved = 0;
        for (Map.Entry<String, String> entry : TICKERS.entrySet()) {
            String symbolTag = entry.getKey();
            if (repository.findBySymbolAndTradingDate(symbolTag, today).isPresent()) {
                continue; // already fetched today
            }
            try {
                ChartResponse response = restClient.get()
                        .uri("/v8/finance/chart/{ticker}?interval=1d&range=5d", entry.getValue())
                        .retrieve()
                        .body(ChartResponse.class);
                Meta meta = extractMeta(response);
                if (meta == null || meta.regularMarketPrice() == null) {
                    log.warn("No usable data from Yahoo for {}", symbolTag);
                    continue;
                }

                GlobalMarketSnapshot snapshot = GlobalMarketSnapshot.builder()
                        .symbol(symbolTag)
                        .tradingDate(today)
                        .fetchedAt(OffsetDateTime.now())
                        .price(meta.regularMarketPrice())
                        .changePct(meta.regularMarketChangePercent())
                        .previousClose(meta.chartPreviousClose())
                        .build();
                repository.save(snapshot);
                saved++;
            } catch (Exception e) {
                log.error("Failed to fetch global market data for {}", symbolTag, e);
            }
        }
        if (saved > 0) {
            log.info("Fetched {} global market snapshot(s)", saved);
        }
        return saved;
    }

    public List<GlobalMarketSnapshot> latest() {
        return TICKERS.keySet().stream()
                .map(repository::findRecentBySymbol)
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .toList();
    }

    private Meta extractMeta(ChartResponse response) {
        if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
            return null;
        }
        return response.chart().result().get(0).meta();
    }

    // Minimal subset of Yahoo's chart response shape — only the fields actually used.
    private record ChartResponse(Chart chart) {
    }

    private record Chart(List<Result> result) {
    }

    private record Result(Meta meta) {
    }

    private record Meta(
            String symbol,
            BigDecimal regularMarketPrice,
            BigDecimal regularMarketChangePercent,
            BigDecimal chartPreviousClose
    ) {
    }
}
