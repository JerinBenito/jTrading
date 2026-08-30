package com.jerin.trading.ingestion;

import java.util.List;

/**
 * NSE trading symbols for the current NIFTY 50 constituents (sourced 2026-08-30) — used only as
 * a basket for the Phase C momentum backtest's cross-sectional sample, not for live tracking.
 * The index is reconstituted semi-annually (end of March/September), so this list will drift
 * slightly stale over time; that's fine for a broad statistical-power basket, but revisit if
 * ever used for something requiring exact index membership.
 */
public final class Nifty50Constituents {

    public static final List<String> SYMBOLS = List.of(
            "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK",
            "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV", "BEL", "BHARTIARTL",
            "CIPLA", "COALINDIA", "DRREDDY", "EICHERMOT", "ETERNAL",
            "GRASIM", "HCLTECH", "HDFCBANK", "HDFCLIFE", "HINDALCO",
            "HINDUNILVR", "ICICIBANK", "INDIGO", "INFY", "ITC",
            "JIOFIN", "JSWSTEEL", "KOTAKBANK", "LT", "M&M",
            "MARUTI", "MAXHEALTH", "NESTLEIND", "NTPC", "ONGC",
            "POWERGRID", "RELIANCE", "SBILIFE", "SHRIRAMFIN", "SBIN",
            "SUNPHARMA", "TCS", "TATACONSUM", "TATAMOTORS", "TATASTEEL",
            "TECHM", "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"
    );

    private Nifty50Constituents() {
    }
}
