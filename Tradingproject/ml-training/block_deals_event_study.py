"""Event study: does a real NSE block deal predict abnormal forward returns for that basket
stock, over the following 1/5/10 trading days? Real data pulled directly from NSE's own
historicalOR API (via the browser, since NSE blocks plain HTTP clients with Akamai bot
protection) for all 50 basket stocks, past year - see block_deals_data.json.

Honest flag before running: many events cluster on the exact same date across dozens of
unrelated stocks (18-Nov-2025, 24-Jun-2026 especially) - almost certainly index/ETF rebalancing,
not differentiated stock-picking signal. Reported both with and without those two dates.
"""
import json
import os
import urllib.request as u
from datetime import datetime
import numpy as np
import pandas as pd
from scipy import stats

API_BASE = "https://jerintradingsignal.duckdns.org"
HERE = os.path.dirname(os.path.abspath(__file__))

with open(os.path.join(HERE, "block_deals_data.json")) as f:
    RAW_BLOCK_DEALS = json.load(f)

MASS_REBALANCE_DATES = {"18-NOV-2025", "24-JUN-2026"}
# TATAMOTORS -> TMPV rename in our own basket; map it so the price-history join works.
SYMBOL_ALIASES = {"TATAMOTORS": "TMPV"}


def get_json(path):
    with u.urlopen(f"{API_BASE}{path}", timeout=60) as resp:
        return json.loads(resp.read())


def load_price_history(symbols):
    frames = []
    for s in symbols:
        rows = get_json(f"/api/ml/features/{s}")
        if rows:
            df = pd.DataFrame(rows)
            df["instrument"] = s
            frames.append(df)
    df = pd.concat(frames, ignore_index=True)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    return df.sort_values(["instrument", "tradingDate"]).reset_index(drop=True)


def event_dates():
    """One event per (symbol, date) - dedupe the buy/sell row pairs into single events."""
    seen = set()
    events = []
    for r in RAW_BLOCK_DEALS:
        sym = SYMBOL_ALIASES.get(r["s"], r["s"])
        key = (sym, r["d"])
        if key in seen:
            continue
        seen.add(key)
        events.append({"symbol": sym, "date_raw": r["d"], "date": datetime.strptime(r["d"], "%d-%b-%Y")})
    return events


def run_study(events, price_history, label):
    print(f"\n=== {label}: {len(events)} distinct (symbol, date) block-deal events ===")
    by_instrument = {inst: g.sort_values("tradingDate").reset_index(drop=True) for inst, g in price_history.groupby("instrument")}

    results = {1: [], 5: [], 10: []}
    baseline_by_symbol = {}
    for inst, g in by_instrument.items():
        rets = g["dailyReturnPct"].dropna()
        if len(rets) > 5:
            baseline_by_symbol[inst] = rets.mean()

    matched = 0
    for ev in events:
        sym = ev["symbol"]
        if sym not in by_instrument or sym not in baseline_by_symbol:
            continue
        hist = by_instrument[sym]
        idx = hist.index[hist["tradingDate"] == pd.Timestamp(ev["date"])]
        if len(idx) == 0:
            prior = hist[hist["tradingDate"] <= pd.Timestamp(ev["date"])]
            if prior.empty:
                continue
            i = prior.index[-1]
        else:
            i = idx[0]
        matched += 1
        for horizon in (1, 5, 10):
            j = i + horizon
            if j >= len(hist):
                continue
            entry = hist.loc[i, "close"]
            exitp = hist.loc[j, "close"]
            if pd.isna(entry) or pd.isna(exitp) or float(entry) == 0:
                continue
            fwd_ret = (float(exitp) - float(entry)) / float(entry) * 100
            excess = fwd_ret - baseline_by_symbol[sym] * horizon
            results[horizon].append(excess)

    print(f"  matched to real price history: {matched}/{len(events)}")
    for horizon, vals in results.items():
        if len(vals) < 5:
            print(f"  {horizon}d: too few events ({len(vals)})")
            continue
        arr = np.array(vals)
        t_stat, p = stats.ttest_1samp(arr, 0)
        print(f"  {horizon}d forward excess return (vs. that stock's own baseline drift): "
              f"n={len(arr):3d}  mean={arr.mean():+.3f}%  t-stat={t_stat:+.2f}  p={p:.4f}")


if __name__ == "__main__":
    events_all = event_dates()
    events_filtered = [e for e in events_all if e["date_raw"] not in MASS_REBALANCE_DATES]
    print(f"Total events: {len(events_all)}, after removing the 2 suspected mass-rebalance dates: {len(events_filtered)}")

    symbols = sorted(set(e["symbol"] for e in events_all))
    prices = load_price_history(symbols)
    print(f"Loaded price history for {prices['instrument'].nunique()} of {len(symbols)} symbols with real block-deal events")

    run_study(events_all, prices, "ALL events (including suspected rebalance-day clusters)")
    run_study(events_filtered, prices, "EXCLUDING the 2 suspected mass-rebalance dates")
