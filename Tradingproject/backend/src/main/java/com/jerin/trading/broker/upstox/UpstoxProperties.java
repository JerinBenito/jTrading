package com.jerin.trading.broker.upstox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "upstox")
public record UpstoxProperties(
        String baseUrl,
        String apiKey,
        String apiSecret,
        String redirectUri
) {
}
