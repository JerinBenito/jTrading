package com.jerin.trading.ingestion;

import com.jerin.trading.forecast.DailyForecastPredictionService;
import com.jerin.trading.forecast.DailyGarchPredictionService;
import com.jerin.trading.forecast.DailyHmmPredictionService;
import com.jerin.trading.forecast.ForecastPredictionService;
import com.jerin.trading.fundamentals.CompanyFundamentalJob;
import com.jerin.trading.fundamentals.CompanyFundamentalService;
import com.jerin.trading.globalmarket.GlobalMarketDataJob;
import com.jerin.trading.globalmarket.GlobalMarketDataService;
import com.jerin.trading.ml.AdaptiveSelectionService;
import com.jerin.trading.ml.AiPredictionService;
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
                                         EquityBasketIngestionService equityBasketIngestionService,
                                         AiPredictionService aiPredictionService,
                                         AdaptiveSelectionService adaptiveSelectionService,
                                         DailyHmmPredictionService dailyHmmPredictionService,
                                         DailyGarchPredictionService dailyGarchPredictionService) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("ingestionService", ingestionService);
        jobDataMap.put("signalService", signalService);
        jobDataMap.put("outcomeEvaluationService", outcomeEvaluationService);
        jobDataMap.put("forecastPredictionService", forecastPredictionService);
        jobDataMap.put("dailyForecastPredictionService", dailyForecastPredictionService);
        jobDataMap.put("futuresIngestionService", futuresIngestionService);
        jobDataMap.put("equityBasketIngestionService", equityBasketIngestionService);
        jobDataMap.put("aiPredictionService", aiPredictionService);
        jobDataMap.put("adaptiveSelectionService", adaptiveSelectionService);
        jobDataMap.put("dailyHmmPredictionService", dailyHmmPredictionService);
        jobDataMap.put("dailyGarchPredictionService", dailyGarchPredictionService);
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

    @Bean
    public JobDetail githubDispatchJobDetail(GithubActionsDispatchService githubActionsDispatchService) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("githubActionsDispatchService", githubActionsDispatchService);
        return JobBuilder.newJob(GithubActionsDispatchJob.class)
                .withIdentity("githubDispatchJob")
                .usingJobData(jobDataMap)
                .storeDurably()
                .build();
    }

    /**
     * Triggers the Daily AI Prediction workflow at a precise time via workflow_dispatch — see
     * {@link GithubActionsDispatchService} for why this replaced relying on GitHub's own
     * schedule: cron (observed running hours late). 9:25 IST, shortly after market open.
     */
    @Bean
    public Trigger githubDispatchTrigger(JobDetail githubDispatchJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(githubDispatchJobDetail)
                .withIdentity("githubDispatchTrigger")
                .withSchedule(CronScheduleBuilder
                        .cronSchedule("0 25 9 ? * MON-FRI")
                        .inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
                .build();
    }

    @Bean
    public JobDetail globalMarketDataJobDetail(GlobalMarketDataService globalMarketDataService) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("globalMarketDataService", globalMarketDataService);
        return JobBuilder.newJob(GlobalMarketDataJob.class)
                .withIdentity("globalMarketDataJob")
                .usingJobData(jobDataMap)
                .storeDurably()
                .build();
    }

    /**
     * 9:05 IST, shortly before market open — by then the just-finished US session, crude oil,
     * and USD/INR are all fully settled (US markets close ~1:30-2:30 AM IST depending on DST),
     * so this captures a clean overnight read before today's own Indian price action starts.
     */
    @Bean
    public Trigger globalMarketDataTrigger(JobDetail globalMarketDataJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(globalMarketDataJobDetail)
                .withIdentity("globalMarketDataTrigger")
                .withSchedule(CronScheduleBuilder
                        .cronSchedule("0 5 9 ? * MON-FRI")
                        .inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
                .build();
    }

    @Bean
    public JobDetail companyFundamentalJobDetail(CompanyFundamentalService companyFundamentalService) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("companyFundamentalService", companyFundamentalService);
        return JobBuilder.newJob(CompanyFundamentalJob.class)
                .withIdentity("companyFundamentalJob")
                .usingJobData(jobDataMap)
                .storeDurably()
                .build();
    }

    /** Once daily, 8:00 IST — well before market open. Fundamentals change quarterly at most,
     * so this is generous, not precision timing; it just needs to run regularly enough to catch
     * updates without hammering the endpoint. */
    @Bean
    public Trigger companyFundamentalTrigger(JobDetail companyFundamentalJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(companyFundamentalJobDetail)
                .withIdentity("companyFundamentalTrigger")
                .withSchedule(CronScheduleBuilder
                        .cronSchedule("0 0 8 ? * MON-FRI")
                        .inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
                .build();
    }
}
