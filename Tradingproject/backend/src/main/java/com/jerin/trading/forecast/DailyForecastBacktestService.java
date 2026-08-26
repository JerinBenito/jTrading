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
 * Walk-forward backtest for same-day open-to-close prediction: at each daily bar, a model
 * predicts *that day's* close using only its own open plus indicators computed through the
 * previous day — never that day's own high/low/close. No lookahead, same discipline as
 * {@link ForecastBacktestService}. Built from the hourly candles already ingested — no
 * separate daily data source needed.
 */
@Service
public class DailyForecastBacktestService {

    private static final int ATR_PERIOD = 14;
    private static final String SOURCE_INTERVAL = "1h";

    private final OhlcvCandleRepository candleRepository;
    private final List<DailyForecastModel> models;

    public DailyForecastBacktestService(OhlcvCandleRepository candleRepository, List<DailyForecastModel> models) {
        this.candleRepository = candleRepository;
        this.models = models;
    }

    public List<ForecastBacktestResult> backtest(Instrument instrument) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), SOURCE_INTERVAL);
        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);
        if (daily.size() < 30) {
            return List.of();
        }

        List<BigDecimal> closes = daily.stream().map(OhlcvCandle::getClose).toList();
        ForecastContext context = new ForecastContext(
                daily,
                EmaCalculator.calculate(closes, 9),
                EmaCalculator.calculate(closes, 21),
                AtrCalculator.calculate(daily, ATR_PERIOD));

        List<ForecastBacktestResult> results = new ArrayList<>();
        for (DailyForecastModel model : models) {
            results.add(evaluate(model, daily, context));
        }
        return results;
    }

    private ForecastBacktestResult evaluate(DailyForecastModel model, List<OhlcvCandle> daily, ForecastContext context) {
        int n = 0;
        double sumAbsErrorPct = 0;
        int withinRange = 0;
        double sumRangeWidthPct = 0;

        for (int i = 1; i < daily.size(); i++) {
            ForecastPrediction prediction = model.predictClose(i, context);
            if (prediction == null) {
                continue;
            }
            BigDecimal actual = daily.get(i).getClose();

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
