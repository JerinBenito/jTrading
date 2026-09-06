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
    private final Path tokenFile;

    public UpstoxTokenStore(@Value("${upstox.token-file:/home/opc/upstox-token.dat}") String tokenFilePath) {
        this.tokenFile = Path.of(tokenFilePath);
        loadFromDisk();
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
        String token = accessToken.get();
        if (token == null) {
            throw new IllegalStateException(
                    "No Upstox access token in memory yet. Log in via GET /auth/upstox/login-url first.");
        }
        return token;
    }
}
