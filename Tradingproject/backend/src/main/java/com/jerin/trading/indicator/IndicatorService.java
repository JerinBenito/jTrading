package com.jerin.trading.indicator;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import com.jerin.trading.repository.OptionChainSnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Service
public class IndicatorService {

    private static final int EMA_SHORT_PERIOD = 9;
    private static final int EMA_LONG_PERIOD = 21;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;

    private final OhlcvCandleRepository candleRepository;
    private final OptionChainSnapshotRepository optionChainRepository;

    public IndicatorService(OhlcvCandleRepository candleRepository,
                             OptionChainSnapshotRepository optionChainRepository) {
        this.candleRepository = candleRepository;
        this.optionChainRepository = optionChainRepository;
    }

    public IndicatorSnapshot latest(Instrument instrument, String interval) {
        List<OhlcvCandle> candles = candleRepository
                .findTop200ByInstrumentAndIntervalOrderByTsDesc(instrument.name(), interval);
        Collections.reverse(candles);

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();

        BigDecimal ema9 = lastNonNull(EmaCalculator.calculate(closes, EMA_SHORT_PERIOD));
        BigDecimal ema21 = lastNonNull(EmaCalculator.calculate(closes, EMA_LONG_PERIOD));
        BigDecimal rsi14 = lastNonNull(RsiCalculator.calculate(closes, RSI_PERIOD));
        BigDecimal atr14 = lastNonNull(AtrCalculator.calculate(candles, ATR_PERIOD));
        BigDecimal pcr = latestPcr(instrument);

        return new IndicatorSnapshot(instrument.name(), interval, candles.size(), ema9, ema21, rsi14, atr14, pcr);
    }

    private BigDecimal latestPcr(Instrument instrument) {
        return optionChainRepository.findLatestTs(instrument.name())
                .map(ts -> PcrCalculator.calculate(optionChainRepository.findByInstrumentAndTs(instrument.name(), ts)))
                .orElse(null);
    }

    private BigDecimal lastNonNull(List<BigDecimal> series) {
        for (int i = series.size() - 1; i >= 0; i--) {
            if (series.get(i) != null) {
                return series.get(i);
            }
        }
        return null;
    }
}
