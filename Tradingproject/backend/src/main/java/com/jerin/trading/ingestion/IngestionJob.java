package com.jerin.trading.ingestion;

import com.jerin.trading.forecast.DailyForecastPredictionService;
import com.jerin.trading.forecast.ForecastPredictionService;
import com.jerin.trading.ml.AdaptiveSelectionService;
import com.jerin.trading.ml.AiPredictionService;
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
    private FuturesIngestionService futuresIngestionService;
    private EquityBasketIngestionService equityBasketIngestionService;
    private AiPredictionService aiPredictionService;
    private AdaptiveSelectionService adaptiveSelectionService;

    public void setIngestionService(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public void setFuturesIngestionService(FuturesIngestionService futuresIngestionService) {
        this.futuresIngestionService = futuresIngestionService;
    }

    public void setEquityBasketIngestionService(EquityBasketIngestionService equityBasketIngestionService) {
        this.equityBasketIngestionService = equityBasketIngestionService;
    }

    public void setAiPredictionService(AiPredictionService aiPredictionService) {
        this.aiPredictionService = aiPredictionService;
    }

    public void setAdaptiveSelectionService(AdaptiveSelectionService adaptiveSelectionService) {
        this.adaptiveSelectionService = adaptiveSelectionService;
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

                int forecastsEvaluated = forecastPredictionService.evaluatePending(instrument.name(), INTERVAL);
                if (forecastsEvaluated > 0) {
                    log.info("{} hourly forecast(s) evaluated for {}", forecastsEvaluated, instrument);
                }
                forecastPredictionService.recordNextPrediction(instrument.name(), INTERVAL);

                int dailyEvaluated = dailyForecastPredictionService.evaluatePending(instrument.name());
                if (dailyEvaluated > 0) {
                    log.info("{} daily forecast(s) evaluated for {}", dailyEvaluated, instrument);
                }
                dailyForecastPredictionService.recordTodayPrediction(instrument.name());

                // Separate try/catch: an adaptive-selection hiccup must never affect the
                // deterministic/AI predictions it merely observes and picks between.
                try {
                    adaptiveSelectionService.recordTodayPrediction(instrument.name());
                } catch (Exception e) {
                    log.error("Adaptive selection failed for {}", instrument, e);
                }
            } catch (Exception e) {
                log.error("Hourly cycle failed for {}", instrument, e);
            }

            // Separate try/catch, deliberately isolated from the core pipeline above — futures
            // ingestion is auxiliary (feeds volume-confirmation analysis only), so a failure
            // here (e.g. a contract rollover edge case) must never affect live signals/forecasts.
            try {
                futuresIngestionService.ingestTodayNearMonth(instrument.name(), instrument.name() + "_FUT", "hours", 1);
            } catch (Exception e) {
                log.error("Futures ingestion failed for {}", instrument, e);
            }
        }

        // Once per cycle, not per Instrument — the basket isn't tied to NIFTY/BANKNIFTY. Isolated
        // from the core pipeline above: a failure ingesting or forecasting one basket stock must
        // never affect NIFTY/BANKNIFTY's live signals/forecasts, or another basket stock's.
        //
        // Deliberately NOT the pattern signal engine — PatternStats is keyed by pattern id alone
        // (no instrument column), so running it against 49 more stocks would corrupt NIFTY/
        // BANKNIFTY's own win-rate stats. Both hourly_predictions tables (this hourly loop AND
        // the same-day forecast) have no such issue — genuinely keyed by instrument — which is
        // what makes both safe to extend here. Hourly forecast added 2026-09-04: previously only
        // ran for NIFTY/BANKNIFTY despite nothing architecturally preventing basket coverage.
        try {
            List<EquityIngestionResult> basketResults = equityBasketIngestionService.ingestTodayForBasket();
            for (EquityIngestionResult result : basketResults) {
                if (!"OK".equals(result.status())) {
                    continue;
                }
                try {
                    dailyForecastPredictionService.evaluatePending(result.symbol());
                    dailyForecastPredictionService.recordTodayPrediction(result.symbol());
                } catch (Exception e) {
                    log.error("Daily forecast cycle failed for basket stock {}", result.symbol(), e);
                }
                try {
                    forecastPredictionService.evaluatePending(result.symbol(), INTERVAL);
                    forecastPredictionService.recordNextPrediction(result.symbol(), INTERVAL);
                } catch (Exception e) {
                    log.error("Hourly forecast cycle failed for basket stock {}", result.symbol(), e);
                }
                try {
                    adaptiveSelectionService.recordTodayPrediction(result.symbol());
                } catch (Exception e) {
                    log.error("Adaptive selection failed for basket stock {}", result.symbol(), e);
                }
            }
        } catch (Exception e) {
            log.error("Equity basket live ingestion failed", e);
        }

        // Evaluates whatever AI predictions (any instrument/horizon) are now knowable — pure
        // read/compare against already-ingested candle data, no model inference happens here.
        // Isolated for the same reason as everything else in this job: never affect the rest.
        try {
            int aiEvaluated = aiPredictionService.evaluatePending();
            if (aiEvaluated > 0) {
                log.info("{} AI prediction(s) evaluated", aiEvaluated);
            }
        } catch (Exception e) {
            log.error("AI prediction evaluation failed", e);
        }
    }
}
