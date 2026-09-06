package com.jerin.trading.ingestion;

import java.util.List;

/**
 * NSE trading symbols for the current NIFTY 50 constituents (sourced 2026-08-30, corrected
 * 2026-09-06) — used only as a basket for the Phase C momentum backtest's cross-sectional
 * sample, not for live tracking. The index is reconstituted semi-annually (end of March/
 * September), so this list will drift slightly stale over time; that's fine for a broad
 * statistical-power basket, but revisit if ever used for something requiring exact index
 * membership.
 *
 * TATAMOTORS -> TMPV (2026-09-06): Tata Motors demerged on 2026-10-24 (this list's original
 * 2026-08-30 source predates that but was apparently never updated for it) into Tata Motors
 * Passenger Vehicles (TMPV, retained NIFTY 50 membership — cars/EVs/Jaguar Land Rover) and
 * Tata Motors Commercial Vehicles (TMCV, not in the index). The old "TATAMOTORS" symbol no
 * longer resolves at all, which meant this entire stock had been silently absent from basket
 * ingestion, monitoring, and forecasting for months before this was caught (surfaced by an
 * ISIN lookup failure while building the fundamentals pipeline). Verified TMPV resolves
 * correctly against Upstox before making this change.
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
            "SUNPHARMA", "TCS", "TATACONSUM", "TMPV", "TATASTEEL",
            "TECHM", "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"
    );

    private Nifty50Constituents() {
    }
}
