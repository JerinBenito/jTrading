package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Walk-forward backtest: at each bar, a model predicts the *next* bar's close using only
 * data up to and including the current bar, then we check what actually happened — no
 * lookahead. Runs against whatever history is already in the DB (currently ~3-4k hourly
 * rows/instrument — comfortably fits in memory on the 1GB VM; would need to switch to
 * paginated/streamed loading if the dataset ever grows by an order of magnitude, e.g. adding
 * minute-level candles or many more instruments — not needed at current volume).
 */
@Service
public class ForecastBacktestService {

    private final OhlcvCandleRepository candleRepository;
    private final List<ForecastModel> models;

    public ForecastBacktestService(OhlcvCandleRepository candleRepository, List<ForecastModel> models) {
        this.candleRepository = candleRepository;
        this.models = models;
    }

    public List<ForecastBacktestResult> backtest(Instrument instrument, String interval) {
        List<OhlcvCandle> candles = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), interval);
        if (candles.size() < 30) {
            return List.of();
        }

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        ForecastContext context = new ForecastContext(
                candles,
                EmaCalculator.calculate(closes, 9),
                EmaCalculator.calculate(closes, 21),
                AtrCalculator.calculate(candles, 14));

        List<ForecastBacktestResult> results = new ArrayList<>();
        for (ForecastModel model : models) {
            results.add(evaluate(model, candles, context));
        }
        return results;
    }

    private ForecastBacktestResult evaluate(ForecastModel model, List<OhlcvCandle> candles, ForecastContext context) {
        int n = 0;
        double sumAbsErrorPct = 0;
        int withinRange = 0;
        double sumRangeWidthPct = 0;

        for (int i = 0; i < candles.size() - 1; i++) {
            ForecastPrediction prediction = model.predictNext(i, context);
            if (prediction == null) {
                continue;
            }
            BigDecimal actual = candles.get(i + 1).getClose();

            double errorPct = actual.subtract(prediction.predictedClose()).abs()
                    .divide(actual, 6, RoundingMode.HALF_UP).doubleValue() * 100;
            sumAbsErrorPct += errorPct;

            if (actual.compareTo(prediction.rangeLow()) >= 0 && actual.compareTo(prediction.rangeHigh()) <= 0) {
                withinRange++;
            }

            double rangeWidthPct = prediction.rangeHigh().subtract(prediction.rangeLow())
                    .divide(prediction.predictedClose(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
            sumRangeWidthPct += rangeWidthPct;

            n++;
        }

        if (n == 0) {
            return new ForecastBacktestResult(model.name(), 0, null, null, null);
        }
        return new ForecastBacktestResult(
                model.name(),
                n,
                BigDecimal.valueOf(sumAbsErrorPct / n).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf((double) withinRange / n * 100).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(sumRangeWidthPct / n).setScale(4, RoundingMode.HALF_UP));
    }
}
