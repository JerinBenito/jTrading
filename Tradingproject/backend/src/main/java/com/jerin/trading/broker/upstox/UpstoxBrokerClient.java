package com.jerin.trading.broker.upstox;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.broker.Candle;
import com.jerin.trading.broker.OptionChainEntry;
import com.jerin.trading.broker.upstox.dto.UpstoxCandleResponse;
import com.jerin.trading.broker.upstox.dto.UpstoxOptionChainResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

@Component
public class UpstoxBrokerClient implements BrokerClient {

    private final RestClient restClient;

    public UpstoxBrokerClient(UpstoxProperties properties, UpstoxTokenStore tokenStore) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Accept", "application/json")
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokenStore.getOrThrow());
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public List<Candle> getCandles(String instrumentKey, String unit, int interval, LocalDate from, LocalDate to) {
        UpstoxCandleResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v3/historical-candle/{instrumentKey}/{unit}/{interval}/{toDate}/{fromDate}")
                        .build(instrumentKey, unit, interval, to, from))
                .retrieve()
                .body(UpstoxCandleResponse.class);

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().candles().stream().map(this::toCandle).toList();
    }

    @Override
    public List<Candle> getIntradayCandles(String instrumentKey, String unit, int interval) {
        UpstoxCandleResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v3/historical-candle/intraday/{instrumentKey}/{unit}/{interval}")
                        .build(instrumentKey, unit, interval))
                .retrieve()
                .body(UpstoxCandleResponse.class);

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().candles().stream().map(this::toCandle).toList();
    }

    private Candle toCandle(List<Object> row) {
        return new Candle(
                OffsetDateTime.parse((String) row.get(0), DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                new BigDecimal(row.get(1).toString()),
                new BigDecimal(row.get(2).toString()),
                new BigDecimal(row.get(3).toString()),
                new BigDecimal(row.get(4).toString()),
                Long.valueOf(row.get(5).toString()),
                Long.valueOf(row.get(6).toString())
        );
    }

    @Override
    public List<OptionChainEntry> getOptionChain(String instrumentKey, String expiry) {
        UpstoxOptionChainResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/option/chain")
                        .queryParam("instrument_key", instrumentKey)
                        .queryParam("expiry_date", expiry)
                        .build())
                .retrieve()
                .body(UpstoxOptionChainResponse.class);

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().stream().map(this::toOptionChainEntry).toList();
    }

    private OptionChainEntry toOptionChainEntry(UpstoxOptionChainResponse.Row row) {
        return new OptionChainEntry(
                LocalDate.parse(row.expiry()),
                row.strikePrice(),
                row.underlyingSpotPrice(),
                toLeg(row.callOptions()),
                toLeg(row.putOptions())
        );
    }

    private OptionChainEntry.OptionLeg toLeg(UpstoxOptionChainResponse.Leg leg) {
        var market = leg.marketData();
        long changeOi = Objects.requireNonNullElse(market.oi(), 0L)
                - Objects.requireNonNullElse(market.prevOi(), 0L);
        BigDecimal iv = leg.optionGreeks() != null ? leg.optionGreeks().iv() : null;
        return new OptionChainEntry.OptionLeg(market.oi(), changeOi, iv, market.ltp(), market.volume());
    }
}
