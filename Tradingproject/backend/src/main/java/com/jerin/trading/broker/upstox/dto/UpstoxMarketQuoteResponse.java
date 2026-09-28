package com.jerin.trading.broker.upstox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Full Market Quotes V3 (GET /v3/market-quote/quotes) — keyed by "EXCHANGE:SYMBOL", not the
 * instrument_key used to request it, so the caller must match rows back up by the symbol field
 * inside each row rather than by map key. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxMarketQuoteResponse(String status, Map<String, Quote> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quote(
            String symbol,
            @JsonProperty("last_price") BigDecimal lastPrice,
            Depth depth,
            @JsonProperty("total_buy_quantity") Long totalBuyQuantity,
            @JsonProperty("total_sell_quantity") Long totalSellQuantity
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Depth(List<Level> buy, List<Level> sell) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Level(Long quantity, BigDecimal price, Long orders) {
    }
}
