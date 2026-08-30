package com.jerin.trading.forecast;

import com.jerin.trading.ingestion.Instrument;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastBacktestService forecastBacktestService;
    private final BiasCorrectionBacktestService biasCorrectionBacktestService;
    private final DailyForecastBacktestService dailyForecastBacktestService;
    private final DailyBiasCorrectionBacktestService dailyBiasCorrectionBacktestService;
    private final RangeCalibrationBacktestService rangeCalibrationBacktestService;
    private final DailyRangeCalibrationBacktestService dailyRangeCalibrationBacktestService;
    private final RangeCalibrationService rangeCalibrationService;
    private final IntradayReanchorBacktestService intradayReanchorBacktestService;
    private final HistoricalAnalogBacktestService historicalAnalogBacktestService;
    private final RichHistoricalAnalogBacktestService richHistoricalAnalogBacktestService;
    private final MomentumBacktestService momentumBacktestService;
    private final PooledMomentumBacktestService pooledMomentumBacktestService;

    public ForecastController(ForecastBacktestService forecastBacktestService,
                               BiasCorrectionBacktestService biasCorrectionBacktestService,
                               DailyForecastBacktestService dailyForecastBacktestService,
                               DailyBiasCorrectionBacktestService dailyBiasCorrectionBacktestService,
                               RangeCalibrationBacktestService rangeCalibrationBacktestService,
                               DailyRangeCalibrationBacktestService dailyRangeCalibrationBacktestService,
                               RangeCalibrationService rangeCalibrationService,
                               IntradayReanchorBacktestService intradayReanchorBacktestService,
                               HistoricalAnalogBacktestService historicalAnalogBacktestService,
                               RichHistoricalAnalogBacktestService richHistoricalAnalogBacktestService,
                               MomentumBacktestService momentumBacktestService,
                               PooledMomentumBacktestService pooledMomentumBacktestService) {
        this.forecastBacktestService = forecastBacktestService;
        this.biasCorrectionBacktestService = biasCorrectionBacktestService;
        this.dailyForecastBacktestService = dailyForecastBacktestService;
        this.dailyBiasCorrectionBacktestService = dailyBiasCorrectionBacktestService;
        this.rangeCalibrationBacktestService = rangeCalibrationBacktestService;
        this.dailyRangeCalibrationBacktestService = dailyRangeCalibrationBacktestService;
        this.rangeCalibrationService = rangeCalibrationService;
        this.intradayReanchorBacktestService = intradayReanchorBacktestService;
        this.historicalAnalogBacktestService = historicalAnalogBacktestService;
        this.richHistoricalAnalogBacktestService = richHistoricalAnalogBacktestService;
        this.momentumBacktestService = momentumBacktestService;
        this.pooledMomentumBacktestService = pooledMomentumBacktestService;
    }

    /** Read-only — computes fresh from stored history each call, nothing persisted (Phase 1 only). */
    @GetMapping("/{instrument}/backtest")
    public List<ForecastBacktestResult> backtest(@PathVariable Instrument instrument,
                                                  @RequestParam(defaultValue = "1h") String interval) {
        return forecastBacktestService.backtest(instrument, interval);
    }

    /** Compares correction strategies (none / naive-20-avg / significance-gated) walk-forward against full history. */
    @GetMapping("/{instrument}/bias-correction-backtest")
    public List<ForecastBacktestResult> biasCorrectionBacktest(@PathVariable Instrument instrument,
                                                                 @RequestParam(defaultValue = "1h") String interval) {
        return biasCorrectionBacktestService.compare(instrument, interval);
    }

    /** Same-day open-to-close: compares candidate models (random walk vs EMA-spread momentum), built from hourly candles.
     *  {@code instrument} accepts NIFTY/BANKNIFTY or any NIFTY 50 basket symbol (Phase D). */
    @GetMapping("/{instrument}/daily-backtest")
    public List<ForecastBacktestResult> dailyBacktest(@PathVariable String instrument) {
        return dailyForecastBacktestService.backtest(instrument);
    }

    /** Same-day open-to-close: compares correction strategies (none / naive-20-avg / significance-gated). */
    @GetMapping("/{instrument}/daily-bias-correction-backtest")
    public List<ForecastBacktestResult> dailyBiasCorrectionBacktest(@PathVariable String instrument) {
        return dailyBiasCorrectionBacktestService.compare(instrument);
    }

    /** Compares the fixed ±1x ATR range against RangeCalibrator's online-adaptive multiplier, hourly. */
    @GetMapping("/{instrument}/range-calibration-backtest")
    public List<RangeCalibrationBacktestResult> rangeCalibrationBacktest(@PathVariable Instrument instrument,
                                                                           @RequestParam(defaultValue = "1h") String interval) {
        return rangeCalibrationBacktestService.compare(instrument, interval);
    }

    /** Same as above, for the same-day open-to-close prediction. */
    @GetMapping("/{instrument}/daily-range-calibration-backtest")
    public List<RangeCalibrationBacktestResult> dailyRangeCalibrationBacktest(@PathVariable String instrument) {
        return dailyRangeCalibrationBacktestService.compare(instrument);
    }

    /** The multiplier currently being applied to new predictions right now, and when it last moved. */
    @GetMapping("/{instrument}/range-calibration")
    public RangeCalibrationStatus rangeCalibrationStatus(@PathVariable String instrument,
                                                           @RequestParam(defaultValue = "1h") String interval) {
        return rangeCalibrationService.status(instrument, interval);
    }

    /** Static (once-a-day) vs. intraday re-anchored same-day close prediction, by hour, plus the "big morning miss" scenario specifically. */
    @GetMapping("/{instrument}/intraday-reanchor-backtest")
    public IntradayReanchorBacktestResult intradayReanchorBacktest(@PathVariable String instrument) {
        return intradayReanchorBacktestService.compare(instrument);
    }

    /** Plain volatility-scaled re-anchor vs. k-NN historical-analog estimate (Phase B — see project long-term roadmap), by hour. */
    @GetMapping("/{instrument}/historical-analog-backtest")
    public List<HistoricalAnalogHourResult> historicalAnalogBacktest(@PathVariable String instrument) {
        return historicalAnalogBacktestService.compare(instrument);
    }

    /** Same as above, plus a richer analog matched on return-so-far + volatility-so-far + RSI + EMA spread, not return alone. */
    @GetMapping("/{instrument}/rich-historical-analog-backtest")
    public List<RichHistoricalAnalogHourResult> richHistoricalAnalogBacktest(@PathVariable String instrument) {
        return richHistoricalAnalogBacktestService.compare(instrument);
    }

    /** Phase C, first test: does past ~2-month return predict the next ~2-month return (time-series momentum)? */
    @GetMapping("/{instrument}/momentum-backtest")
    public MomentumBacktestResult momentumBacktest(@PathVariable Instrument instrument) {
        return momentumBacktestService.compare(instrument);
    }

    /** Same momentum test as above, but pooled across NIFTY + BANKNIFTY + the backfilled NIFTY 50 basket for a larger cross-sectional sample. */
    @GetMapping("/pooled-momentum-backtest")
    public PooledMomentumBacktestResult pooledMomentumBacktest() {
        return pooledMomentumBacktestService.compare();
    }
}
