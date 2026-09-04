package com.jerin.trading.globalmarket;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

/** Instantiated by Quartz — see {@code QuartzConfig#globalMarketDataTrigger}. */
public class GlobalMarketDataJob implements Job {

    private GlobalMarketDataService globalMarketDataService;

    public void setGlobalMarketDataService(GlobalMarketDataService globalMarketDataService) {
        this.globalMarketDataService = globalMarketDataService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        globalMarketDataService.fetchToday();
    }
}
