package com.jerin.trading.broker.upstox;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UpstoxAuthController {

    private final UpstoxAuthService authService;

    public UpstoxAuthController(UpstoxAuthService authService) {
        this.authService = authService;
    }

    /**
     * Open the returned URL in a browser and log in with your Upstox credentials + TOTP.
     * Upstox then redirects to the configured redirect_uri with a `code` query param —
     * pass that code to /auth/upstox/callback.
     */
    @GetMapping("/auth/upstox/login-url")
    public Map<String, String> loginUrl() {
        return Map.of("loginUrl", authService.buildLoginUrl());
    }

    @GetMapping("/auth/upstox/callback")
    public Map<String, String> callback(@RequestParam String code) {
        authService.exchangeCodeForToken(code);
        return Map.of("status", "ok");
    }
}
