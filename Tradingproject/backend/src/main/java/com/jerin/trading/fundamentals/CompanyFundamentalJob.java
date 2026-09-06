package com.jerin.trading.fundamentals;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

/** Instantiated by Quartz — see {@code QuartzConfig#companyFundamentalTrigger}. */
public class CompanyFundamentalJob implements Job {

    private CompanyFundamentalService companyFundamentalService;

    public void setCompanyFundamentalService(CompanyFundamentalService companyFundamentalService) {
        this.companyFundamentalService = companyFundamentalService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        companyFundamentalService.fetchAllBasketSymbols();
    }
}
