package com.jerin.trading.ingestion;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

/** Instantiated by Quartz - see {@link QuartzConfig#dailyScreenDispatchTrigger}. */
public class DailyScreenDispatchJob implements Job {

    private GithubActionsDispatchService githubActionsDispatchService;

    public void setGithubActionsDispatchService(GithubActionsDispatchService githubActionsDispatchService) {
        this.githubActionsDispatchService = githubActionsDispatchService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        githubActionsDispatchService.triggerDailyScreen();
    }
}
