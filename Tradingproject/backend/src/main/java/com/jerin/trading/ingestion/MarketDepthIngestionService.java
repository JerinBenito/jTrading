package com.jerin.trading.ingestion;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.domain.MarketDepthSnapshot;
import com.jerin.trading.repository.MarketDepthSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Captures one live order-book snapshot per basket stock per call — the start of a real, honest
 * history for order-flow imbalance, which (unlike everything else tried this project) is
 * genuinely new information, not another derivative of OHLCV. Deliberately observational only:
 * nothing reads this table yet, and nothing should until it has enough real history (weeks/months)
 * to test honestly, the same discipline every other feature in this system went through.
 */
@Service
public class MarketDepthIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MarketDepthIngestionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BrokerClient brokerClient;
    private final EquityBasketIngestionService equityBasketIngestionService;
    private final MarketDepthSnapshotRepository repository;

    public MarketDepthIngestionService(BrokerClient brokerClient,
                                        EquityBasketIngestionService equityBasketIngestionService,
                                        MarketDepthSnapshotRepository repository) {
        this.brokerClient = brokerClient;
        this.equityBasketIngestionService = equityBasketIngestionService;
        this.repository = repository;
    }

    /** One batched call covers the whole basket (Upstox allows up to 500 instrument_keys per
     * request; the basket is 50) rather than one request per symbol. */
    public int ingestBasketSnapshot() {
        Map<String, String> keyToSymbol = new HashMap<>();
        List<String> instrumentKeys = new ArrayList<>();
        for (String symbol : Nifty50Constituents.SYMBOLS) {
            Optional<String> key = equityBasketIngestionService.findInstrumentKey(symbol);
            if (key.isPresent()) {
                keyToSymbol.put(key.get(), symbol);
                instrumentKeys.add(key.get());
            }
        }
        if (instrumentKeys.isEmpty()) {
            log.warn("No resolved instrument keys — skipping market depth snapshot");
            return 0;
        }

        List<BrokerClient.MarketQuote> quotes = brokerClient.getMarketQuotes(instrumentKeys);
        OffsetDateTime ts = OffsetDateTime.now(IST);

        int saved = 0;
        for (BrokerClient.MarketQuote quote : quotes) {
            // The quote's own symbol field is the Upstox trading symbol, which is what the rest
            // of this codebase already uses as the instrument tag - no lookup needed here.
            repository.save(MarketDepthSnapshot.builder()
                    .instrument(quote.symbol())
                    .ts(ts)
                    .lastPrice(quote.lastPrice())
                    .totalBuyQuantity(quote.totalBuyQuantity())
                    .totalSellQuantity(quote.totalSellQuantity())
                    .imbalance(imbalance(quote.totalBuyQuantity(), quote.totalSellQuantity()))
                    .topBidPrice(quote.topBidPrice())
                    .topBidQuantity(quote.topBidQuantity())
                    .topAskPrice(quote.topAskPrice())
                    .topAskQuantity(quote.topAskQuantity())
                    .build());
            saved++;
        }
        log.info("Captured {} market depth snapshots ({} instrument keys requested)", saved, instrumentKeys.size());
        return saved;
    }

    private BigDecimal imbalance(Long buy, Long sell) {
        if (buy == null || sell == null) {
            return null;
        }
        long total = buy + sell;
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(buy - sell)
                .divide(BigDecimal.valueOf(total), new MathContext(6, RoundingMode.HALF_UP));
    }
}
