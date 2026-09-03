package com.jerin.trading.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Fires the "Daily AI Prediction" GitHub Actions workflow via its {@code workflow_dispatch} API
 * instead of waiting on GitHub's own {@code schedule:} cron. GitHub's schedule trigger is
 * observed to be unreliable on this low-activity free-tier repo — real runs landed hours late
 * (once near market close), even after widening the cron window to fire every 10 minutes.
 * workflow_dispatch (an on-demand/API-triggered run) isn't subject to that same deprioritization,
 * so this VM's own already-reliable Quartz scheduler (see {@link QuartzConfig}) is used purely
 * for precise timing, while the actual training still runs on GitHub's runners — not this VM's
 * tiny free-tier RAM.
 *
 * No-ops with a warning if {@code github-dispatch.token} isn't configured, so the app runs fine
 * without it until the token is set up.
 */
@Service
public class GithubActionsDispatchService {

    private static final Logger log = LoggerFactory.getLogger(GithubActionsDispatchService.class);

    private final RestClient restClient;
    private final GithubActionsProperties properties;

    public GithubActionsDispatchService(GithubActionsProperties properties) {
        this.restClient = RestClient.builder().baseUrl("https://api.github.com").build();
        this.properties = properties;
    }

    public void triggerDailyAiPrediction() {
        if (properties.token() == null || properties.token().isBlank()) {
            log.warn("github-dispatch.token not configured — skipping workflow_dispatch trigger; "
                    + "the workflow's own (unreliable) schedule: cron is the only trigger left.");
            return;
        }
        try {
            // "owner/repo" must be two separate path segments — templating it into one {repo}
            // variable makes RestClient percent-encode the "/" as %2F, which GitHub 404s on.
            String[] ownerAndRepo = properties.repo().split("/", 2);
            restClient.post()
                    .uri("/repos/{owner}/{repoName}/actions/workflows/{workflowFile}/dispatches",
                            ownerAndRepo[0], ownerAndRepo[1], properties.workflowFile())
                    .header("Authorization", "Bearer " + properties.token())
                    .header("Accept", "application/vnd.github+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ref", "main"))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Triggered Daily AI Prediction workflow via workflow_dispatch");
        } catch (Exception e) {
            log.error("Failed to trigger Daily AI Prediction workflow", e);
        }
    }
}
