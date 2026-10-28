package com.jerin.trading.ingestion;

import com.jerin.trading.options.StockOptionSummaryService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

/** Instantiated by Quartz - see {@link QuartzConfig#stockOptionSummaryTrigger}. */
public class StockOptionSummaryJob implements Job {

    private StockOptionSummaryService stockOptionSummaryService;

    public void setStockOptionSummaryService(StockOptionSummaryService stockOptionSummaryService) {
        this.stockOptionSummaryService = stockOptionSummaryService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        stockOptionSummaryService.captureAll();
    }
}
