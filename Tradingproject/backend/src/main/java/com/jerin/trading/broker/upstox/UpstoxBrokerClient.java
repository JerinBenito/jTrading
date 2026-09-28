package com.jerin.trading.broker.upstox;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.broker.Candle;
import com.jerin.trading.broker.FuturesContract;
import com.jerin.trading.broker.OptionChainEntry;
import com.jerin.trading.broker.upstox.dto.UpstoxCandleResponse;
import com.jerin.trading.broker.upstox.dto.UpstoxInstrumentSearchResponse;
import com.jerin.trading.broker.upstox.dto.UpstoxKeyRatiosResponse;
import com.jerin.trading.broker.upstox.dto.UpstoxMarketQuoteResponse;
import com.jerin.trading.broker.upstox.dto.UpstoxOptionChainResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    @Override
    public Optional<FuturesContract> findNearMonthFuture(String underlyingSymbol) {
        UpstoxInstrumentSearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/instruments/search")
                        .queryParam("query", underlyingSymbol)
                        .queryParam("segments", "FO")
                        .queryParam("instrument_types", "FUT")
                        .build())
                .retrieve()
                .body(UpstoxInstrumentSearchResponse.class);

        if (response == null || response.data() == null) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now();
        return response.data().stream()
                .filter(row -> "FUT".equals(row.instrumentType()))
                .filter(row -> underlyingSymbol.equalsIgnoreCase(row.underlyingSymbol()))
                .filter(row -> row.expiry() != null && !LocalDate.parse(row.expiry()).isBefore(today))
                .min(Comparator.comparing(row -> LocalDate.parse(row.expiry())))
                .map(row -> new FuturesContract(row.instrumentKey(), row.tradingSymbol(), LocalDate.parse(row.expiry())));
    }

    @Override
    public Optional<String> findEquityInstrumentKey(String tradingSymbol) {
        return searchEquity(tradingSymbol).map(UpstoxInstrumentSearchResponse.Row::instrumentKey);
    }

    @Override
    public Optional<String> findEquityIsin(String tradingSymbol) {
        return searchEquity(tradingSymbol).map(UpstoxInstrumentSearchResponse.Row::isin);
    }

    private Optional<UpstoxInstrumentSearchResponse.Row> searchEquity(String tradingSymbol) {
        UpstoxInstrumentSearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/instruments/search")
                        .queryParam("query", tradingSymbol)
                        .queryParam("segments", "EQ")
                        .queryParam("instrument_types", "EQ")
                        .queryParam("exchanges", "NSE")
                        .build())
                .retrieve()
                .body(UpstoxInstrumentSearchResponse.class);

        if (response == null || response.data() == null) {
            return Optional.empty();
        }

        return response.data().stream()
                .filter(row -> "EQ".equals(row.instrumentType()))
                .filter(row -> tradingSymbol.equalsIgnoreCase(row.tradingSymbol()))
                .findFirst();
    }

    @Override
    public List<MarketQuote> getMarketQuotes(List<String> instrumentKeys) {
        if (instrumentKeys.isEmpty()) {
            return List.of();
        }
        UpstoxMarketQuoteResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v3/market-quote/quotes")
                        .queryParam("instrument_key", String.join(",", instrumentKeys))
                        .build())
                .retrieve()
                .body(UpstoxMarketQuoteResponse.class);

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> toMarketQuote(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** {@code mapKey} looks like "NSE_EQ:RELIANCE" — used as a fallback when the row's own
     * {@code symbol} field is blank, rather than trust it unconditionally. */
    private MarketQuote toMarketQuote(String mapKey, UpstoxMarketQuoteResponse.Quote quote) {
        String symbol = quote.symbol() != null && !quote.symbol().isBlank()
                ? quote.symbol()
                : mapKey.substring(mapKey.indexOf(':') + 1);
        var depth = quote.depth();
        var topBid = depth != null && depth.buy() != null && !depth.buy().isEmpty() ? depth.buy().get(0) : null;
        var topAsk = depth != null && depth.sell() != null && !depth.sell().isEmpty() ? depth.sell().get(0) : null;
        return new MarketQuote(
                symbol,
                quote.lastPrice(),
                quote.totalBuyQuantity(),
                quote.totalSellQuantity(),
                topBid != null ? topBid.price() : null,
                topBid != null ? topBid.quantity() : null,
                topAsk != null ? topAsk.price() : null,
                topAsk != null ? topAsk.quantity() : null
        );
    }

    @Override
    public List<KeyRatio> getKeyRatios(String isin) {
        UpstoxKeyRatiosResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/fundamentals/{isin}/key-ratios")
                        .build(isin))
                .retrieve()
                .body(UpstoxKeyRatiosResponse.class);

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().stream()
                .map(row -> new KeyRatio(row.name(), row.companyValue(), row.sectorValue()))
                .toList();
    }
}
