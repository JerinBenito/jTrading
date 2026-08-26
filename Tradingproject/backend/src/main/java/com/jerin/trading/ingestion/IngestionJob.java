package com.jerin.trading.ingestion;

import com.jerin.trading.forecast.DailyForecastPredictionService;
import com.jerin.trading.forecast.ForecastPredictionService;
import com.jerin.trading.signal.OutcomeEvaluationService;
import com.jerin.trading.signal.SignalResponse;
import com.jerin.trading.signal.SignalService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Instantiated by Quartz (not a Spring bean) — services are injected via the JobDataMap
 * set up in QuartzConfig, using SpringBeanJobFactory's default property binding.
 *
 * Runs the full hourly cycle per instrument: ingest new data, check whether a pattern
 * just fired on the latest bar (persisting a signal if so), evaluate outcomes for any past
 * pattern predictions, evaluate any hourly forecast whose target hour just happened, record
 * a new hourly forecast for the next hour, and do the same for the same-day open-to-close
 * forecast (record once at the day's first candle, evaluate once that day is over — both
 * idempotent, safe to call every cycle). Nothing here requires a manual API call — this is
 * what makes the system run unattended.
 */
public class IngestionJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(IngestionJob.class);
    private static final String INTERVAL = "1h";

    private IngestionService ingestionService;
    private SignalService signalService;
    private OutcomeEvaluationService outcomeEvaluationService;
    private ForecastPredictionService forecastPredictionService;
    private DailyForecastPredictionService dailyForecastPredictionService;

    public void setIngestionService(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public void setSignalService(SignalService signalService) {
        this.signalService = signalService;
    }

    public void setOutcomeEvaluationService(OutcomeEvaluationService outcomeEvaluationService) {
        this.outcomeEvaluationService = outcomeEvaluationService;
    }

    public void setForecastPredictionService(ForecastPredictionService forecastPredictionService) {
        this.forecastPredictionService = forecastPredictionService;
    }

    public void setDailyForecastPredictionService(DailyForecastPredictionService dailyForecastPredictionService) {
        this.dailyForecastPredictionService = dailyForecastPredictionService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        for (Instrument instrument : Instrument.values()) {
            try {
                ingestionService.ingestIntradayCandles(instrument, "hours", 1);
                ingestionService.ingestOptionChain(instrument, instrument.optionChainExpiry());

                List<SignalResponse> signals = signalService.generateSignals(instrument, INTERVAL);
                if (!signals.isEmpty()) {
                    log.info("{} signal(s) generated for {}: {}", signals.size(), instrument, signals);
                }

                int evaluated = outcomeEvaluationService.evaluatePending(instrument, INTERVAL);
                if (evaluated > 0) {
                    log.info("{} outcome(s) evaluated for {}", evaluated, instrument);
                }

                int forecastsEvaluated = forecastPredictionService.evaluatePending(instrument, INTERVAL);
                if (forecastsEvaluated > 0) {
                    log.info("{} hourly forecast(s) evaluated for {}", forecastsEvaluated, instrument);
                }
                forecastPredictionService.recordNextPrediction(instrument, INTERVAL);

                int dailyEvaluated = dailyForecastPredictionService.evaluatePending(instrument);
                if (dailyEvaluated > 0) {
                    log.info("{} daily forecast(s) evaluated for {}", dailyEvaluated, instrument);
                }
                dailyForecastPredictionService.recordTodayPrediction(instrument);
            } catch (Exception e) {
                log.error("Hourly cycle failed for {}", instrument, e);
            }
        }
    }
}
