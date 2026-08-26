package com.jerin.trading.broker;

import java.time.LocalDate;
import java.util.List;

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
}
