package com.jerin.trading.ml;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.domain.OptionChainSnapshot;
import com.jerin.trading.domain.SignalPrediction;
import com.jerin.trading.fundamentals.CompanyFundamentalRepository;
import com.jerin.trading.globalmarket.GlobalMarketSnapshot;
import com.jerin.trading.globalmarket.GlobalMarketSnapshotRepository;
import com.jerin.trading.indicator.PcrCalculator;
import com.jerin.trading.repository.HourlyPredictionRepository;
import com.jerin.trading.repository.OptionChainSnapshotRepository;
import com.jerin.trading.repository.SignalPredictionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Joins every other model/data source this codebase has built — the deterministic/HMM/GARCH
 * daily calls, options PCR, overnight global market context, company fundamentals, and the
 * most recent pattern signal's track record — onto a per-day feature set, so the AI model's
 * training can actually see them instead of training on price/candle data alone. Building the
 * lookup maps is the expensive part; {@link #contextFor} does that once per instrument, and
 * {@link Context#forDay} is then a cheap per-day/per-hour lookup.
 */
@Service
public class SupplementaryFeatureService {

    private static final String DAILY_INTERVAL = "1d";
    private static final String HMM_INTERVAL = "1d_hmm";
    private static final String GARCH_INTERVAL = "1d_garch";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final HourlyPredictionRepository hourlyPredictionRepository;
    private final OptionChainSnapshotRepository optionChainSnapshotRepository;
    private final GlobalMarketSnapshotRepository globalMarketSnapshotRepository;
    private final CompanyFundamentalRepository companyFundamentalRepository;
    private final SignalPredictionRepository signalPredictionRepository;

    public SupplementaryFeatureService(HourlyPredictionRepository hourlyPredictionRepository,
                                        OptionChainSnapshotRepository optionChainSnapshotRepository,
                                        GlobalMarketSnapshotRepository globalMarketSnapshotRepository,
                                        CompanyFundamentalRepository companyFundamentalRepository,
                                        SignalPredictionRepository signalPredictionRepository) {
        this.hourlyPredictionRepository = hourlyPredictionRepository;
        this.optionChainSnapshotRepository = optionChainSnapshotRepository;
        this.globalMarketSnapshotRepository = globalMarketSnapshotRepository;
        this.companyFundamentalRepository = companyFundamentalRepository;
        this.signalPredictionRepository = signalPredictionRepository;
    }

    public Context contextFor(String instrument) {
        return new Context(
                byPredictedForDay(instrument, DAILY_INTERVAL),
                byPredictedForDay(instrument, HMM_INTERVAL),
                byPredictedForDay(instrument, GARCH_INTERVAL),
                pcrByDay(instrument),
                globalChangeByDay("SP500"),
                globalChangeByDay("CRUDE_OIL"),
                globalChangeByDay("USD_INR"),
                fundamentalValue(instrument, "P/E"),
                fundamentalValue(instrument, "ROE"),
                mostRecentSignalByDay(instrument));
    }

    public static final class Context {
        private final Map<LocalDate, HourlyPrediction> deterministicByDay;
        private final Map<LocalDate, HourlyPrediction> hmmByDay;
        private final Map<LocalDate, HourlyPrediction> garchByDay;
        private final NavigableMap<LocalDate, Double> pcrByDay;
        private final NavigableMap<LocalDate, Double> sp500ByDay;
        private final NavigableMap<LocalDate, Double> crudeOilByDay;
        private final NavigableMap<LocalDate, Double> usdInrByDay;
        private final Double fundamentalPe;
        private final Double fundamentalRoe;
        private final NavigableMap<LocalDate, SignalPrediction> patternByDay;

        private Context(Map<LocalDate, HourlyPrediction> deterministicByDay,
                         Map<LocalDate, HourlyPrediction> hmmByDay,
                         Map<LocalDate, HourlyPrediction> garchByDay,
                         NavigableMap<LocalDate, Double> pcrByDay,
                         NavigableMap<LocalDate, Double> sp500ByDay,
                         NavigableMap<LocalDate, Double> crudeOilByDay,
                         NavigableMap<LocalDate, Double> usdInrByDay,
                         Double fundamentalPe, Double fundamentalRoe,
                         NavigableMap<LocalDate, SignalPrediction> patternByDay) {
            this.deterministicByDay = deterministicByDay;
            this.hmmByDay = hmmByDay;
            this.garchByDay = garchByDay;
            this.pcrByDay = pcrByDay;
            this.sp500ByDay = sp500ByDay;
            this.crudeOilByDay = crudeOilByDay;
            this.usdInrByDay = usdInrByDay;
            this.fundamentalPe = fundamentalPe;
            this.fundamentalRoe = fundamentalRoe;
            this.patternByDay = patternByDay;
        }

        public SupplementaryFeatures forDay(LocalDate day, BigDecimal dayOpen) {
            Double patternWinRate = null;
            Integer patternDirection = null;
            Map.Entry<LocalDate, SignalPrediction> patternEntry = patternByDay.floorEntry(day);
            if (patternEntry != null) {
                SignalPrediction p = patternEntry.getValue();
                Object winRateObj = p.getInputsSnapshot().get("patternWinRate");
                if (winRateObj instanceof Number n) {
                    patternWinRate = n.doubleValue();
                }
                patternDirection = "up".equals(p.getPredictedDirection()) ? 1 : -1;
            }

            return new SupplementaryFeatures(
                    deviationPct(deterministicByDay.get(day), dayOpen),
                    deviationPct(hmmByDay.get(day), dayOpen),
                    rangeWidthPct(garchByDay.get(day)),
                    floorValue(pcrByDay, day),
                    floorValue(sp500ByDay, day),
                    floorValue(crudeOilByDay, day),
                    floorValue(usdInrByDay, day),
                    fundamentalPe,
                    fundamentalRoe,
                    patternWinRate,
                    patternDirection);
        }

        private static Double deviationPct(HourlyPrediction prediction, BigDecimal dayOpen) {
            if (prediction == null || dayOpen == null || dayOpen.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return prediction.getPredictedClose().subtract(dayOpen)
                    .divide(dayOpen, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }

        private static Double rangeWidthPct(HourlyPrediction prediction) {
            if (prediction == null || prediction.getPredictedClose() == null
                    || prediction.getPredictedClose().compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return prediction.getRangeHigh().subtract(prediction.getRangeLow())
                    .divide(prediction.getPredictedClose(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }

        private static Double floorValue(NavigableMap<LocalDate, Double> map, LocalDate day) {
            Map.Entry<LocalDate, Double> entry = map.floorEntry(day);
            return entry != null ? entry.getValue() : null;
        }
    }

    private Map<LocalDate, HourlyPrediction> byPredictedForDay(String instrument, String interval) {
        Map<LocalDate, HourlyPrediction> map = new HashMap<>();
        for (HourlyPrediction p : hourlyPredictionRepository.findByInstrumentAndIntervalOrderByPredictedForTsDesc(instrument, interval)) {
            map.putIfAbsent(p.getPredictedForTs().atZoneSameInstant(IST).toLocalDate(), p);
        }
        return map;
    }

    /** Groups every snapshot row by its exact timestamp (one chain snapshot = many strike rows
     * sharing one ts), computes PCR per snapshot, then keeps the latest same-day snapshot's PCR
     * per date — ascending ts order means a later put for the same date always wins. */
    private NavigableMap<LocalDate, Double> pcrByDay(String instrument) {
        List<OptionChainSnapshot> all = optionChainSnapshotRepository.findByInstrumentOrderByTsAsc(instrument);
        Map<OffsetDateTime, List<OptionChainSnapshot>> byTs = new LinkedHashMap<>();
        for (OptionChainSnapshot row : all) {
            byTs.computeIfAbsent(row.getTs(), k -> new ArrayList<>()).add(row);
        }
        NavigableMap<LocalDate, Double> result = new TreeMap<>();
        for (Map.Entry<OffsetDateTime, List<OptionChainSnapshot>> entry : byTs.entrySet()) {
            BigDecimal pcr = PcrCalculator.calculate(entry.getValue());
            if (pcr != null) {
                result.put(entry.getKey().atZoneSameInstant(IST).toLocalDate(), pcr.doubleValue());
            }
        }
        return result;
    }

    private NavigableMap<LocalDate, Double> globalChangeByDay(String symbol) {
        NavigableMap<LocalDate, Double> result = new TreeMap<>();
        for (GlobalMarketSnapshot s : globalMarketSnapshotRepository.findRecentBySymbol(symbol)) {
            if (s.getChangePct() != null) {
                result.put(s.getTradingDate(), s.getChangePct().doubleValue());
            }
        }
        return result;
    }

    /** Current value only (no historical series exists) — see {@link SupplementaryFeatures}'
     * javadoc for the resulting lookahead caveat on historical rows. */
    private Double fundamentalValue(String instrument, String ratioName) {
        return companyFundamentalRepository.findBySymbolAndRatioName(instrument, ratioName)
                .map(f -> parseNumeric(f.getCompanyValue()))
                .orElse(null);
    }

    private Double parseNumeric(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Most recent signal at or before each date — keeps the latest same-day signal since
     * descending-ts traversal means the first entry seen per date is the latest one. */
    private NavigableMap<LocalDate, SignalPrediction> mostRecentSignalByDay(String instrument) {
        NavigableMap<LocalDate, SignalPrediction> result = new TreeMap<>();
        for (SignalPrediction p : signalPredictionRepository.findByInstrumentOrderByTsDesc(instrument)) {
            result.putIfAbsent(p.getTs().atZoneSameInstant(IST).toLocalDate(), p);
        }
        return result;
    }
}
