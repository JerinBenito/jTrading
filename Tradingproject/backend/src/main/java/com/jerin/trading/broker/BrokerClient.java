package com.jerin.trading.broker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BrokerClient {

    /**
     * Past, *completed* trading days only — the broker's historical endpoint never returns
     * data for the current in-progress trading day (returns empty, not an error).
     * @param unit one of "minutes", "hours", "days", "weeks", "months"
     * @param interval size of each candle within that unit, e.g. unit="hours", interval=1
     */
    List<Candle> getCandles(String instrumentKey, String unit, int interval, LocalDate from, LocalDate to);

    /**
     * Today's forming/completed-so-far candles for the current trading session — this is
     * what the live hourly ingestion job needs; {@link #getCandles} would silently return
     * nothing for "today" since that's a completed-days-only endpoint.
     * @param unit one of "minutes", "hours", "days"
     * @param interval size within that unit (broker-specific range, e.g. hours supports 1-5)
     */
    List<Candle> getIntradayCandles(String instrumentKey, String unit, int interval);

    /**
     * @param expiry an ISO date (YYYY-MM-DD) or a broker-supported keyword such as "current_week"
     */
    List<OptionChainEntry> getOptionChain(String instrumentKey, String expiry);

    /**
     * The nearest-expiry (soonest not-yet-expired) tradable futures contract for a given
     * underlying — e.g. "NIFTY" or "BANKNIFTY". Unlike the underlying index itself, a futures
     * contract carries real trading volume.
     */
    Optional<FuturesContract> findNearMonthFuture(String underlyingSymbol);

    /** @return the NSE equity instrument_key for a trading symbol (e.g. "RELIANCE"), if listed */
    Optional<String> findEquityInstrumentKey(String tradingSymbol);

    /** @return the ISIN for a trading symbol (e.g. "RELIANCE" -> "INE002A01018"), if listed —
     * needed to call the fundamentals endpoints, which are keyed by ISIN, not instrument_key. */
    Optional<String> findEquityIsin(String tradingSymbol);

    /** Key financial ratios (P/E, P/B, ROA, ROE, ROCE, EV/EBITDA, ...) for a company, each with
     * a sector benchmark alongside it. */
    List<KeyRatio> getKeyRatios(String isin);

    /** Live market depth (top-of-book buy/sell levels and aggregate quantities) for up to 500
     * instrument_keys in a single call. Live-only — the broker has no historical order-book
     * endpoint, so this can only ever describe "right now", never be backfilled for past dates. */
    List<MarketQuote> getMarketQuotes(List<String> instrumentKeys);

    record KeyRatio(String name, String companyValue, String sectorValue) {
    }

    /** One instrument's live order-book snapshot. {@code totalBuyQuantity}/{@code totalSellQuantity}
     * are the broker's own aggregate across all depth levels — imbalance = (buy - sell) / (buy +
     * sell), the standard order-flow-imbalance definition. Top-of-book price/qty kept separately
     * since they're the cheapest, most liquid part of the book to act on. */
    record MarketQuote(
            String symbol,
            BigDecimal lastPrice,
            Long totalBuyQuantity,
            Long totalSellQuantity,
            BigDecimal topBidPrice,
            Long topBidQuantity,
            BigDecimal topAskPrice,
            Long topAskQuantity
    ) {
    }
}
