package com.jerin.trading.broker.upstox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Upstox access tokens expire daily (fixed to a specific time each day) and can only be
 * obtained through the browser-based OAuth login (see UpstoxAuthService) — there is no
 * password grant, so a fresh login is unavoidably needed once per day regardless of this
 * class. What this DOES fix: the token used to live in memory only, so every app restart
 * (e.g. a deploy) wiped it and forced a re-login even within the same day, even though the
 * token itself was still perfectly valid. Now persisted to a local file so it survives
 * restarts — read once at startup, rewritten on every successful login.
 */
@Component
public class UpstoxTokenStore {

    private static final Logger log = LoggerFactory.getLogger(UpstoxTokenStore.class);

    private final AtomicReference<String> accessToken = new AtomicReference<>();
    /**
     * Upstox "Analytics Token": a read-only token valid for one year, generated once in the Upstox Developer
     * Apps console (Analytics tab) - no daily OAuth login. It covers market quotes, historical/intraday candles,
     * the option chain, fundamentals and the market-data websocket, which is everything this app reads. When the
     * file exists it is PREFERRED over the daily OAuth token, so an accidental OAuth login cannot replace a
     * one-year token with a one-day one. Added 2026-10-24 after the daily login lapsed and stopped data collection.
     */
    private final AtomicReference<String> analyticsToken = new AtomicReference<>();
    private final Path tokenFile;
    private final Path analyticsTokenFile;

    public UpstoxTokenStore(@Value("${upstox.token-file:/home/opc/upstox-token.dat}") String tokenFilePath,
                            @Value("${upstox.analytics-token-file:/home/opc/upstox-analytics-token.dat}") String analyticsTokenFilePath) {
        this.tokenFile = Path.of(tokenFilePath);
        this.analyticsTokenFile = Path.of(analyticsTokenFilePath);
        loadFromDisk();
        loadAnalyticsToken();
    }

    private void loadAnalyticsToken() {
        try {
            if (Files.exists(analyticsTokenFile)) {
                String token = Files.readString(analyticsTokenFile).strip();
                if (!token.isBlank()) {
                    analyticsToken.set(token);
                    log.info("Using the long-lived Upstox Analytics Token from {} (no daily login needed; "
                            + "valid one year from when it was generated)", analyticsTokenFile);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read the Upstox Analytics Token from {} - falling back to the daily login token",
                    analyticsTokenFile, e);
        }
    }

    /** True when a one-year Analytics Token is in use (so the daily OAuth login is not needed). */
    public boolean usingAnalyticsToken() {
        return analyticsToken.get() != null;
    }

    private void loadFromDisk() {
        try {
            if (Files.exists(tokenFile)) {
                String token = Files.readString(tokenFile).strip();
                if (!token.isBlank()) {
                    accessToken.set(token);
                    log.info("Loaded persisted Upstox token from {} — still subject to Upstox's own daily "
                            + "expiry, so a fresh login may still be needed if it's from a previous day", tokenFile);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load persisted Upstox token from {} — starting with none in memory", tokenFile, e);
        }
    }

    public void set(String token) {
        accessToken.set(token);
        try {
            Files.writeString(tokenFile, token);
        } catch (IOException e) {
            log.warn("Failed to persist Upstox token to {} — it will still work for this process, "
                    + "but won't survive a restart", tokenFile, e);
        }
    }

    public String getOrThrow() {
        String analytics = analyticsToken.get();
        if (analytics != null) {
            return analytics;
        }
        String token = accessToken.get();
        if (token == null) {
            throw new IllegalStateException(
                    "No Upstox access token in memory yet. Log in via GET /auth/upstox/login-url first.");
        }
        return token;
    }
}
