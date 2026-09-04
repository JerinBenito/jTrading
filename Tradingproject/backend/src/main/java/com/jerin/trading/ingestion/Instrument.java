package com.jerin.trading.ingestion;

/**
 * MVP scope is NIFTY and BANKNIFTY only (per project build order) — extend this as
 * later phases add instruments.
 */
public enum Instrument {

    // NSE has discontinued weekly options for both indices (BANKNIFTY earlier; NIFTY's
    // "current_week" started silently returning zero rows too as of ~2026-09-01, confirmed
    // live against Upstox: current_week=0 rows, current_month=246 rows) — monthly only now.
    NIFTY("NSE_INDEX|Nifty 50", "current_month"),
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
