package com.jerin.trading.broker.upstox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxCandleResponse(String status, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(List<List<Object>> candles) {
    }
}
