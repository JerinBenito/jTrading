package com.jerin.trading.broker.upstox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxTokenResponse(@JsonProperty("access_token") String accessToken) {
}
