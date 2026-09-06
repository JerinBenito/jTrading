package com.jerin.trading.fundamentals;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.ingestion.EquityBasketIngestionService;
import com.jerin.trading.ingestion.Nifty50Constituents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Fetches key financial ratios (P/E, P/B, ROA, ROE, ROCE, EV/EBITDA, ...) for the NIFTY 50
 * basket stocks from Upstox's Company Fundamentals API — verified live and free, keyed by
 * ISIN. Purely observational: not wired into any prediction or signal yet, same discipline as
 * every other new data source here (option chain / PCR sat unused as a passive indicator for
 * weeks before ever becoming a signal, and there still isn't enough option-chain history to
 * test one honestly as of 2026-09-04). Fundamentals change quarterly at most, so this just
 * needs to run regularly enough to catch updates — daily is generous, not because the data
 * moves that fast.
 */
@Service
public class CompanyFundamentalService {

    private static final Logger log = LoggerFactory.getLogger(CompanyFundamentalService.class);

    private final BrokerClient brokerClient;
    private final EquityBasketIngestionService equityBasketIngestionService;
    private final CompanyFundamentalRepository repository;

    public CompanyFundamentalService(BrokerClient brokerClient,
                                      EquityBasketIngestionService equityBasketIngestionService,
                                      CompanyFundamentalRepository repository) {
        this.brokerClient = brokerClient;
        this.equityBasketIngestionService = equityBasketIngestionService;
        this.repository = repository;
    }

    /** One basket stock at a time, isolated — a failure resolving one symbol's ISIN or fetching
     * its ratios must never affect any other symbol. */
    @Transactional
    public int fetchForSymbol(String symbol) {
        Optional<String> isin = equityBasketIngestionService.findIsin(symbol);
        if (isin.isEmpty()) {
            log.warn("No ISIN found for {} — skipping fundamentals fetch", symbol);
            return 0;
        }

        List<BrokerClient.KeyRatio> ratios = brokerClient.getKeyRatios(isin.get());
        OffsetDateTime now = OffsetDateTime.now();
        int saved = 0;
        for (BrokerClient.KeyRatio ratio : ratios) {
            CompanyFundamental row = repository.findBySymbolAndRatioName(symbol, ratio.name())
                    .orElseGet(CompanyFundamental::new);
            row.setSymbol(symbol);
            row.setIsin(isin.get());
            row.setRatioName(ratio.name());
            row.setCompanyValue(ratio.companyValue());
            row.setSectorValue(ratio.sectorValue());
            row.setFetchedAt(now);
            repository.save(row);
            saved++;
        }
        return saved;
    }

    /** All 49 basket stocks, one at a time — isolated per symbol so one bad ISIN lookup or API
     * hiccup can't take down the rest of the batch. */
    public int fetchAllBasketSymbols() {
        int totalSaved = 0;
        for (String symbol : Nifty50Constituents.SYMBOLS) {
            try {
                totalSaved += fetchForSymbol(symbol);
            } catch (Exception e) {
                log.error("Fundamentals fetch failed for {}", symbol, e);
            }
        }
        if (totalSaved > 0) {
            log.info("Fetched {} fundamental ratio value(s) across the basket", totalSaved);
        }
        return totalSaved;
    }

    public List<CompanyFundamental> forSymbol(String symbol) {
        return repository.findBySymbolOrderByRatioNameAsc(symbol);
    }
}
