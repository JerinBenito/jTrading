package com.jerin.trading.broker.upstox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxKeyRatiosResponse(String status, List<Row> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            String name,
            @JsonProperty("company_value") String companyValue,
            @JsonProperty("sector_value") String sectorValue
    ) {
    }
}
