"""Statistical arbitrage / pairs trading: find pairs of instruments whose prices historically
move together (cointegration), and test whether the SPREAD between them mean-reverts - a
genuinely different hypothesis than anything else tried in this project (predicting one
instrument's own future price/return). Different math too: Engle-Granger cointegration test +
z-score mean reversion of a linear combination of two prices, not a return-prediction model.

Critical discipline point: testing ~1,275 pairs (51 choose 2) for cointegration WILL produce real
false positives by chance alone even at a strict p-value threshold (at p<0.05, expect ~64 "hits"
from pure noise). So pair *discovery* happens only on an in-sample period; every candidate pair
found there is then re-tested on a strictly later, never-seen out-of-sample period before being
called real - the same "don't trust it until it survives data it wasn't fit on" discipline used
everywhere else in this project.
"""
import itertools
import json
import os
import urllib.parse
import urllib.request

import numpy as np
import pandas as pd
from statsmodels.tsa.stattools import coint

API_BASE = "https://jerintradingsignal.duckdns.org"
IN_SAMPLE_PVALUE_THRESHOLD = 0.01  # strict, since discovery alone isn't proof - OOS validation is
Z_ENTRY_THRESHOLD = 2.0
FORWARD_DAYS = 10  # does the spread move back toward zero within this many days of an extreme reading?


def get_symbols():
    with urllib.request.urlopen(f"{API_BASE}/api/monitor/basket", timeout=30) as resp:
        return [row["symbol"] for row in json.loads(resp.read())]


def download_price_history(symbols):
    print(f"Downloading daily close history for {len(symbols)} instruments...")
    all_rows = []
    for sym in symbols:
        url = f"{API_BASE}/api/ml/features/{urllib.parse.quote(sym)}"
        with urllib.request.urlopen(url, timeout=30) as resp:
            rows = json.loads(resp.read())
        for r in rows:
            all_rows.append({"instrument": sym, "tradingDate": r["tradingDate"], "close": r["close"]})
    df = pd.DataFrame(all_rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    wide = df.pivot(index="tradingDate", columns="instrument", values="close").sort_index()
    return wide


def find_cointegrated_pairs(prices_in_sample, symbols):
    print(f"\nScreening {len(list(itertools.combinations(symbols, 2)))} pairs for cointegration "
          f"(in-sample, p < {IN_SAMPLE_PVALUE_THRESHOLD})...")
    candidates = []
    for a, b in itertools.combinations(symbols, 2):
        series_a = prices_in_sample[a].dropna()
        series_b = prices_in_sample[b].dropna()
        common = series_a.index.intersection(series_b.index)
        if len(common) < 200:
            continue
        sa, sb = series_a.loc[common], series_b.loc[common]
        try:
            _, pvalue, _ = coint(sa, sb)
        except Exception:
            continue
        if pvalue < IN_SAMPLE_PVALUE_THRESHOLD:
            hedge_ratio = np.polyfit(sb, sa, 1)[0]
            candidates.append((a, b, pvalue, hedge_ratio))

    candidates.sort(key=lambda c: c[2])
    print(f"Found {len(candidates)} in-sample candidate pairs (out of "
          f"{len(list(itertools.combinations(symbols, 2)))} tested)")
    return candidates


def validate_out_of_sample(candidates, prices_in_sample, prices_out_sample):
    print(f"\nValidating each candidate on OUT-OF-SAMPLE data (never used for pair discovery)...")
    print(f"{'Pair':<30}{'IS p-value':<12}{'Hedge ratio':<14}{'OOS signals':<14}{'Reverted %':<12}")
    results = []
    for a, b, pvalue, hedge_ratio in candidates:
        # Spread mean/std come from the IN-SAMPLE period only - never refit on OOS data, or this
        # would just be curve-fitting the validation set too.
        spread_in = prices_in_sample[a] - hedge_ratio * prices_in_sample[b]
        spread_mean, spread_std = spread_in.mean(), spread_in.std()
        if spread_std < 1e-9:
            continue

        spread_out = (prices_out_sample[a] - hedge_ratio * prices_out_sample[b]).dropna()
        z_out = (spread_out - spread_mean) / spread_std

        signals = z_out[z_out.abs() > Z_ENTRY_THRESHOLD]
        if len(signals) < 5:
            continue

        reverted = 0
        checked = 0
        z_values = z_out.values
        z_index = list(z_out.index)
        for sig_date in signals.index:
            i = z_index.index(sig_date)
            if i + FORWARD_DAYS >= len(z_values):
                continue
            start_abs_z = abs(z_values[i])
            future_abs_z = abs(z_values[i + FORWARD_DAYS])
            checked += 1
            if future_abs_z < start_abs_z:
                reverted += 1

        if checked < 5:
            continue
        reverted_pct = reverted / checked * 100
        results.append((a, b, pvalue, hedge_ratio, checked, reverted_pct))
        print(f"{a}/{b:<25}{pvalue:<12.6f}{hedge_ratio:<14.4f}{checked:<14}{reverted_pct:<12.2f}")

    return results


def run():
    symbols = get_symbols()
    prices = download_price_history(symbols)
    print(f"Price matrix: {prices.shape[0]} trading days x {prices.shape[1]} instruments")
    print(f"Date range: {prices.index.min().date()} to {prices.index.max().date()}")

    cutoff_idx = int(len(prices) * 0.6)
    cutoff_date = prices.index[cutoff_idx]
    prices_in_sample = prices.iloc[:cutoff_idx]
    prices_out_sample = prices.iloc[cutoff_idx:]
    print(f"\nIn-sample (pair discovery): {prices_in_sample.index.min().date()} to {prices_in_sample.index.max().date()} ({len(prices_in_sample)} days)")
    print(f"Out-of-sample (validation): {prices_out_sample.index.min().date()} to {prices_out_sample.index.max().date()} ({len(prices_out_sample)} days)")

    candidates = find_cointegrated_pairs(prices_in_sample, symbols)
    if not candidates:
        print("\nNo candidate pairs passed the in-sample cointegration screen. Stopping.")
        return

    results = validate_out_of_sample(candidates, prices_in_sample, prices_out_sample)

    print(f"\n{'=' * 70}")
    if not results:
        print("No candidate pair produced enough out-of-sample signals to evaluate.")
        return

    real_pairs = [r for r in results if r[5] > 55]  # meaningfully better than the 50% coin-flip
    print(f"Of {len(candidates)} in-sample candidates, {len(results)} had enough OOS signals to test.")
    print(f"{len(real_pairs)} showed real out-of-sample reversion (>55% of extreme readings moved "
          f"back toward zero within {FORWARD_DAYS} days).")
    if real_pairs:
        print("\nPairs worth a closer look:")
        for a, b, pvalue, hedge_ratio, checked, reverted_pct in sorted(real_pairs, key=lambda r: -r[5]):
            print(f"  {a}/{b}: {reverted_pct:.1f}% reversion rate over {checked} OOS signals "
                  f"(in-sample cointegration p={pvalue:.6f})")


if __name__ == "__main__":
    run()
