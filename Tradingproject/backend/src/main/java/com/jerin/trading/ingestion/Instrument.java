package com.jerin.trading.ingestion;

/**
 * MVP scope is NIFTY and BANKNIFTY only (per project build order) — extend this as
 * later phases add instruments.
 */
public enum Instrument {

    // NSE discontinued weekly BANKNIFTY options (monthly only now) — "current_week" silently
    // returns zero rows for it, so each instrument gets its own valid expiry keyword.
    NIFTY("NSE_INDEX|Nifty 50", "current_week"),
    BANKNIFTY("NSE_INDEX|Nifty Bank", "current_month");

    private final String brokerKey;
    private final String optionChainExpiry;

    Instrument(String brokerKey, String optionChainExpiry) {
        this.brokerKey = brokerKey;
        this.optionChainExpiry = optionChainExpiry;
    }

    public String brokerKey() {
        return brokerKey;
    }

    public String optionChainExpiry() {
        return optionChainExpiry;
    }
}
