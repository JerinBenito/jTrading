package com.jerin.trading.ingestion;

import com.jerin.trading.forecast.DailyForecastPredictionService;
import com.jerin.trading.forecast.ForecastPredictionService;
import com.jerin.trading.signal.OutcomeEvaluationService;
import com.jerin.trading.signal.SignalService;
import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class QuartzConfig {

    @Bean
    public JobDetail ingestionJobDetail(IngestionService ingestionService, SignalService signalService,
                                         OutcomeEvaluationService outcomeEvaluationService,
                                         ForecastPredictionService forecastPredictionService,
                                         DailyForecastPredictionService dailyForecastPredictionService,
                                         FuturesIngestionService futuresIngestionService,
                                         EquityBasketIngestionService equityBasketIngestionService) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("ingestionService", ingestionService);
        jobDataMap.put("signalService", signalService);
        jobDataMap.put("outcomeEvaluationService", outcomeEvaluationService);
        jobDataMap.put("forecastPredictionService", forecastPredictionService);
        jobDataMap.put("dailyForecastPredictionService", dailyForecastPredictionService);
        jobDataMap.put("futuresIngestionService", futuresIngestionService);
        jobDataMap.put("equityBasketIngestionService", equityBasketIngestionService);
        return JobBuilder.newJob(IngestionJob.class)
                .withIdentity("ingestionJob")
                .usingJobData(jobDataMap)
                .storeDurably()
                .build();
    }

    /**
     * Hourly, on the hour, NSE market hours (9am-3pm IST), weekdays only.
     * Approximates the 9:15-3:30 trading window at hourly granularity per MVP scope.
     */
    @Bean
    public Trigger ingestionJobTrigger(JobDetail ingestionJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(ingestionJobDetail)
                .withIdentity("ingestionJobTrigger")
                .withSchedule(CronScheduleBuilder
                        .cronSchedule("0 0 9-15 ? * MON-FRI")
                        .inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
                .build();
    }

    /**
     * One extra run shortly after market close (15:45 IST) — the 15:00 run only sees data up
     * to ~14:15, so the day's final candle (~15:15) and the prediction targeting it never get
     * ingested/evaluated by the regular hourly schedule. Same job, safe to run an extra time:
     * ingestion dedupes by exact timestamp, forecast prediction dedupes by target hour, and
     * isLikelyTradingHour correctly stops it from creating a bogus post-close prediction.
     */
    @Bean
    public Trigger endOfDayCatchUpTrigger(JobDetail ingestionJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(ingestionJobDetail)
                .withIdentity("endOfDayCatchUpTrigger")
                .withSchedule(CronScheduleBuilder
                        .cronSchedule("0 45 15 ? * MON-FRI")
                        .inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
                .build();
    }
}
