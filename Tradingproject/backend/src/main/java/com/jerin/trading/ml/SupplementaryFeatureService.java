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

    private static final String AI_INTRADAY_HORIZON = "INTRADAY";

    private final HourlyPredictionRepository hourlyPredictionRepository;
    private final OptionChainSnapshotRepository optionChainSnapshotRepository;
    private final GlobalMarketSnapshotRepository globalMarketSnapshotRepository;
    private final CompanyFundamentalRepository companyFundamentalRepository;
    private final SignalPredictionRepository signalPredictionRepository;
    private final AiPredictionRepository aiPredictionRepository;
    private final AiPredictionSnapshotRepository aiPredictionSnapshotRepository;

    public SupplementaryFeatureService(HourlyPredictionRepository hourlyPredictionRepository,
                                        OptionChainSnapshotRepository optionChainSnapshotRepository,
                                        GlobalMarketSnapshotRepository globalMarketSnapshotRepository,
                                        CompanyFundamentalRepository companyFundamentalRepository,
                                        SignalPredictionRepository signalPredictionRepository,
                                        AiPredictionRepository aiPredictionRepository,
                                        AiPredictionSnapshotRepository aiPredictionSnapshotRepository) {
        this.hourlyPredictionRepository = hourlyPredictionRepository;
        this.optionChainSnapshotRepository = optionChainSnapshotRepository;
        this.globalMarketSnapshotRepository = globalMarketSnapshotRepository;
        this.companyFundamentalRepository = companyFundamentalRepository;
        this.signalPredictionRepository = signalPredictionRepository;
        this.aiPredictionRepository = aiPredictionRepository;
        this.aiPredictionSnapshotRepository = aiPredictionSnapshotRepository;
    }

    public Context contextFor(String instrument) {
        List<AiPredictionSnapshot> aiSnapshots = aiPredictionSnapshotRepository
                .findByInstrumentAndHorizonOrderByTargetDateAscPredictedAtTsAsc(instrument, AI_INTRADAY_HORIZON);
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
                mostRecentSignalByDay(instrument),
                aiErrorByDay(instrument),
                aiRevisionGradientByDay(aiSnapshots),
                aiFirstCallErrorByDay(aiSnapshots));
    }

    /** The AI's own realized error, keyed by target_date — {@link Context#forDay} looks this up
     * with {@code lowerEntry} (strictly before), never {@code floorEntry}, since a day's own
     * outcome isn't knowable at the moment it's being predicted. */
    private record AiErrorInfo(double errorPct, int directionCorrect) {
    }

    private NavigableMap<LocalDate, AiErrorInfo> aiErrorByDay(String instrument) {
        NavigableMap<LocalDate, AiErrorInfo> result = new TreeMap<>();
        for (AiPrediction p : aiPredictionRepository.findAllEvaluatedOrderByTargetDateAsc(instrument, AI_INTRADAY_HORIZON)) {
            if (p.getActualPrice() == null || p.getActualPrice().compareTo(BigDecimal.ZERO) == 0
                    || p.getAiErrorAbs() == null || p.getDirectionCorrect() == null) {
                continue;
            }
            // baseline == actual means the call was made after the close was known (see
            // AiPredictionService.INTRADAY_CUTOFF) — its "error" and "direction" are artifacts of
            // that, not a real outcome, and must never reach the model as a training signal.
            if (p.getBaselineValue().compareTo(p.getActualValue()) == 0) {
                continue;
            }
            double errorPct = p.getAiErrorAbs().divide(p.getActualPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
            result.put(p.getTargetDate(), new AiErrorInfo(errorPct, Boolean.TRUE.equals(p.getDirectionCorrect()) ? 1 : 0));
        }
        return result;
    }

    /** The earliest valid (pre-close) snapshot per target_date. A post-close call already knows the
     * close, so it is neither a real first call nor a real revision. */
    private NavigableMap<LocalDate, AiPredictionSnapshot> firstValidByDay(List<AiPredictionSnapshot> snapshots) {
        NavigableMap<LocalDate, AiPredictionSnapshot> first = new TreeMap<>();
        for (AiPredictionSnapshot s : snapshots) { // ascending order: first seen per day is the earliest call
            if (!s.isAfterClose()) {
                first.putIfAbsent(s.getTargetDate(), s);
            }
        }
        return first;
    }

    /** How far, and which way, the AI moved its own prediction between its first and last valid
     * call, per target_date: the last call's STORED deviation from the first, as % of the first
     * call. Signed. Same "strictly before" lookup rule as {@link #aiErrorByDay}. */
    private NavigableMap<LocalDate, Double> aiRevisionGradientByDay(List<AiPredictionSnapshot> snapshots) {
        NavigableMap<LocalDate, AiPredictionSnapshot> firstByDay = firstValidByDay(snapshots);
        NavigableMap<LocalDate, AiPredictionSnapshot> lastByDay = new TreeMap<>();
        for (AiPredictionSnapshot s : snapshots) { // ascending order: last write per day is the latest call
            if (!s.isAfterClose()) {
                lastByDay.put(s.getTargetDate(), s);
            }
        }
        NavigableMap<LocalDate, Double> result = new TreeMap<>();
        for (Map.Entry<LocalDate, AiPredictionSnapshot> entry : firstByDay.entrySet()) {
            AiPredictionSnapshot first = entry.getValue();
            AiPredictionSnapshot last = lastByDay.get(entry.getKey());
            if (last == null || first.getPredictedValue().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal deviation = last.getDeviationFromFirst() != null
                    ? last.getDeviationFromFirst()
                    : last.getPredictedValue().subtract(first.getPredictedValue());
            result.put(entry.getKey(), deviation.divide(first.getPredictedValue(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue());
        }
        return result;
    }

    /** The FIRST valid call's stored signed error (% of the actual), per evaluated target_date —
     * the fair, least-information call, not the near-close one. Same "strictly before" lookup rule. */
    private NavigableMap<LocalDate, Double> aiFirstCallErrorByDay(List<AiPredictionSnapshot> snapshots) {
        NavigableMap<LocalDate, Double> result = new TreeMap<>();
        for (Map.Entry<LocalDate, AiPredictionSnapshot> entry : firstValidByDay(snapshots).entrySet()) {
            if (entry.getValue().getErrorPct() != null) {
                result.put(entry.getKey(), entry.getValue().getErrorPct().doubleValue());
            }
        }
        return result;
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
        private final NavigableMap<LocalDate, AiErrorInfo> aiErrorByDay;
        private final NavigableMap<LocalDate, Double> aiRevisionGradientByDay;
        private final NavigableMap<LocalDate, Double> aiFirstCallErrorByDay;

        private Context(Map<LocalDate, HourlyPrediction> deterministicByDay,
                         Map<LocalDate, HourlyPrediction> hmmByDay,
                         Map<LocalDate, HourlyPrediction> garchByDay,
                         NavigableMap<LocalDate, Double> pcrByDay,
                         NavigableMap<LocalDate, Double> sp500ByDay,
                         NavigableMap<LocalDate, Double> crudeOilByDay,
                         NavigableMap<LocalDate, Double> usdInrByDay,
                         Double fundamentalPe, Double fundamentalRoe,
                         NavigableMap<LocalDate, SignalPrediction> patternByDay,
                         NavigableMap<LocalDate, AiErrorInfo> aiErrorByDay,
                         NavigableMap<LocalDate, Double> aiRevisionGradientByDay,
                         NavigableMap<LocalDate, Double> aiFirstCallErrorByDay) {
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
            this.aiErrorByDay = aiErrorByDay;
            this.aiRevisionGradientByDay = aiRevisionGradientByDay;
            this.aiFirstCallErrorByDay = aiFirstCallErrorByDay;
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

            // Strictly before `day` — unlike the other lookups above, this one must never see the
            // day's own outcome, since that isn't knowable at the moment `day` is being predicted.
            Map.Entry<LocalDate, AiErrorInfo> priorError = aiErrorByDay.lowerEntry(day);
            Double aiPriorDayErrorPct = priorError != null ? priorError.getValue().errorPct() : null;
            Integer aiPriorDayDirectionCorrect = priorError != null ? priorError.getValue().directionCorrect() : null;
            Map.Entry<LocalDate, Double> priorGradient = aiRevisionGradientByDay.lowerEntry(day);
            Double aiPriorDayRevisionGradientPct = priorGradient != null ? priorGradient.getValue() : null;
            Map.Entry<LocalDate, Double> priorFirstCallError = aiFirstCallErrorByDay.lowerEntry(day);
            Double aiPriorDayFirstCallErrorPct = priorFirstCallError != null ? priorFirstCallError.getValue() : null;

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
                    patternDirection,
                    aiPriorDayErrorPct,
                    aiPriorDayDirectionCorrect,
                    aiPriorDayRevisionGradientPct,
                    aiPriorDayFirstCallErrorPct);
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
