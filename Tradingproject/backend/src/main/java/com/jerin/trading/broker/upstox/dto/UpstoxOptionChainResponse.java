package com.jerin.trading.broker.upstox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxOptionChainResponse(String status, List<Row> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            String expiry,
            @JsonProperty("strike_price") BigDecimal strikePrice,
            @JsonProperty("underlying_spot_price") BigDecimal underlyingSpotPrice,
            @JsonProperty("call_options") Leg callOptions,
            @JsonProperty("put_options") Leg putOptions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(
            @JsonProperty("market_data") MarketData marketData,
            @JsonProperty("option_greeks") OptionGreeks optionGreeks
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketData(
            BigDecimal ltp,
            Long volume,
            Long oi,
            @JsonProperty("prev_oi") Long prevOi
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionGreeks(BigDecimal iv) {
    }
}
