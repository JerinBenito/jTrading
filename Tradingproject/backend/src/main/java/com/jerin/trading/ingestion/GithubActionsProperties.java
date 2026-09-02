package com.jerin.trading.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for reliably triggering the Daily AI Prediction workflow ourselves instead of relying
 * on GitHub's own {@code schedule:} cron — see {@link GithubActionsDispatchService}.
 */
@ConfigurationProperties(prefix = "github-dispatch")
public record GithubActionsProperties(
        String token,
        String repo,
        String workflowFile
) {
}
