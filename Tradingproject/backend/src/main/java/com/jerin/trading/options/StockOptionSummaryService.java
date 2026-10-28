package com.jerin.trading.options;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.broker.OptionChainEntry;
import com.jerin.trading.ingestion.EquityBasketIngestionService;
import com.jerin.trading.ingestion.Nifty50Constituents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Records a compact summary of each basket stock's option chain (nearest monthly expiry at least {@value #MIN_DAYS_TO_EXPIRY}
 * days out) every hour: the broker only serves the current chain, so history exists only from the day this started.
 * Stock derivatives expire on the last Tuesday of the month; when that day is a holiday the exchange moves it earlier, so the
 * day before is tried as well.
 */
@Service
public class StockOptionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(StockOptionSummaryService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int MIN_DAYS_TO_EXPIRY = 5;

    private final BrokerClient brokerClient;
    private final EquityBasketIngestionService basketService;
    private final StockOptionSummaryRepository repository;

    public StockOptionSummaryService(BrokerClient brokerClient, EquityBasketIngestionService basketService, StockOptionSummaryRepository repository) {
        this.brokerClient = brokerClient;
        this.basketService = basketService;
        this.repository = repository;
    }

    /** Captures and stores every basket stock; one stock failing never stops the others. Returns how many were stored. */
    public int captureAll() {
        int stored = 0;
        for (String symbol : Nifty50Constituents.SYMBOLS) {
            try {
                if (capture(symbol).isPresent()) {
                    stored++;
                }
                Thread.sleep(150);                                  // stay well inside the broker's rate limit
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warn("Option summary failed for {}: {}", symbol, e.toString());
            }
        }
        log.info("Stored {} stock option summaries", stored);
        return stored;
    }

    public Optional<StockOptionSummary> capture(String symbol) {
        Optional<String> key = basketService.findInstrumentKey(symbol);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(IST);
        for (LocalDate expiry : candidateExpiries(today)) {
            List<OptionChainEntry> chain = brokerClient.getOptionChain(key.get(), expiry.toString());
            if (chain.isEmpty()) {
                continue;
            }
            StockOptionSummary summary = summarize(symbol, chain, expiry, today);
            if (summary != null) {
                return Optional.of(repository.save(summary));
            }
        }
        return Optional.empty();
    }

    /** The last Tuesday of this month and next, plus the day before each (holiday shift), nearest first and at least 5 days out. */
    static List<LocalDate> candidateExpiries(LocalDate today) {
        List<LocalDate> out = new ArrayList<>();
        for (int m = 0; m < 3 && out.size() < 4; m++) {
            LocalDate lastTuesday = today.plusMonths(m).with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY));
            if (ChronoUnit.DAYS.between(today, lastTuesday) >= MIN_DAYS_TO_EXPIRY) {
                out.add(lastTuesday);
                out.add(lastTuesday.minusDays(1));
            }
        }
        return out;
    }

    static StockOptionSummary summarize(String symbol, List<OptionChainEntry> chain, LocalDate expiry, LocalDate today) {
        BigDecimal spot = chain.stream().map(OptionChainEntry::underlyingSpotPrice).filter(x -> x != null && x.signum() > 0).findFirst().orElse(null);
        if (spot == null) {
            return null;
        }
        OptionChainEntry atm = nearest(chain, spot);
        BigDecimal callIv = positive(atm.call().iv());
        BigDecimal putIv = positive(atm.put().iv());
        BigDecimal atmIv = mean(callIv, putIv);
        OptionChainEntry lowStrike = nearest(chain, spot.multiply(BigDecimal.valueOf(0.95)));
        OptionChainEntry highStrike = nearest(chain, spot.multiply(BigDecimal.valueOf(1.05)));
        BigDecimal skew = null;
        BigDecimal pIv = positive(lowStrike.put().iv());
        BigDecimal cIv = positive(highStrike.call().iv());
        if (pIv != null && cIv != null) {
            skew = pIv.subtract(cIv);
        }
        long putOi = 0;
        long callOi = 0;
        for (OptionChainEntry e : chain) {
            putOi += e.put().oi() == null ? 0 : e.put().oi();
            callOi += e.call().oi() == null ? 0 : e.call().oi();
        }
        BigDecimal pcr = callOi > 0 ? BigDecimal.valueOf(putOi).divide(BigDecimal.valueOf(callOi), 4, RoundingMode.HALF_UP) : null;
        BigDecimal straddlePct = null;
        if (atm.call().ltp() != null && atm.put().ltp() != null) {
            straddlePct = atm.call().ltp().add(atm.put().ltp()).multiply(BigDecimal.valueOf(100)).divide(spot, 4, RoundingMode.HALF_UP);
        }
        return StockOptionSummary.builder()
                .instrument(symbol).capturedAt(OffsetDateTime.now(IST)).expiry(expiry)
                .daysToExpiry((int) ChronoUnit.DAYS.between(today, expiry)).spot(spot).atmStrike(atm.strikePrice())
                .callIv(callIv).putIv(putIv).atmIv(atmIv).skew(skew).pcrOi(pcr).totalOi(putOi + callOi).straddlePct(straddlePct)
                .build();
    }

    private static OptionChainEntry nearest(List<OptionChainEntry> chain, BigDecimal target) {
        return chain.stream().min(Comparator.comparing(e -> e.strikePrice().subtract(target).abs())).orElseThrow();
    }

    private static BigDecimal positive(BigDecimal v) {
        return v != null && v.signum() > 0 ? v : null;
    }

    private static BigDecimal mean(BigDecimal a, BigDecimal b) {
        if (a != null && b != null) {
            return a.add(b).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        }
        return a != null ? a : b;
    }
}
