package com.jerin.trading.broker.upstox;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Upstox access tokens expire daily and can only be obtained through the browser-based
 * OAuth login (see UpstoxAuthService) — there is no password grant. This holds the token
 * in memory for the current process; it must be re-populated each trading day via
 * GET /auth/upstox/callback.
 */
@Component
public class UpstoxTokenStore {

    private final AtomicReference<String> accessToken = new AtomicReference<>();

    public void set(String token) {
        accessToken.set(token);
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
