package com.jerin.trading.broker.upstox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxInstrumentSearchResponse(String status, List<Row> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            String name,
            String segment,
            String expiry,
            @JsonProperty("instrument_key") String instrumentKey,
            @JsonProperty("trading_symbol") String tradingSymbol,
            @JsonProperty("instrument_type") String instrumentType,
            @JsonProperty("underlying_symbol") String underlyingSymbol
    ) {
    }
}
