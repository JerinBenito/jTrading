"""Second, independent check on test_pairs_trading.py's candidate pairs: swaps which period is
used for discovery vs. validation (later period discovers, earlier period validates - reversed
from the original run) and re-runs the full screen from scratch. A pair that's genuinely real
should show up as a significant candidate AND show real reversion regardless of which direction
time runs in the test; a pair that only appeared because of one particular split's idiosyncrasies
(like ASIANPAINT's story-specific trend) should not survive having the split reversed.
"""
import itertools
import json
import urllib.parse
import urllib.request

import numpy as np
import pandas as pd
from statsmodels.tsa.stattools import coint

API_BASE = "https://jerintradingsignal.duckdns.org"
IN_SAMPLE_PVALUE_THRESHOLD = 0.01
Z_ENTRY_THRESHOLD = 2.0
FORWARD_DAYS = 10
WATCH_PAIRS = {("HDFCBANK", "HDFCLIFE"), ("COALINDIA", "NTPC"), ("COALINDIA", "POWERGRID")}


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
    return df.pivot(index="tradingDate", columns="instrument", values="close").sort_index()


def find_cointegrated_pairs(prices_discovery, symbols):
    pairs = list(itertools.combinations(symbols, 2))
    print(f"\nScreening {len(pairs)} pairs for cointegration on the DISCOVERY period "
          f"(p < {IN_SAMPLE_PVALUE_THRESHOLD})...")
    candidates = []
    for a, b in pairs:
        sa_full, sb_full = prices_discovery[a].dropna(), prices_discovery[b].dropna()
        common = sa_full.index.intersection(sb_full.index)
        if len(common) < 200:
            continue
        sa, sb = sa_full.loc[common], sb_full.loc[common]
        try:
            _, pvalue, _ = coint(sa, sb)
        except Exception:
            continue
        if pvalue < IN_SAMPLE_PVALUE_THRESHOLD:
            hedge_ratio = np.polyfit(sb, sa, 1)[0]
            candidates.append((a, b, pvalue, hedge_ratio))
    candidates.sort(key=lambda c: c[2])
    print(f"Found {len(candidates)} candidates (out of {len(pairs)} pairs tested)")
    return candidates


def validate(candidates, prices_discovery, prices_validation):
    print("\nValidating on the (swapped) held-out period...")
    results = []
    for a, b, pvalue, hedge_ratio in candidates:
        spread_disc = prices_discovery[a] - hedge_ratio * prices_discovery[b]
        spread_mean, spread_std = spread_disc.mean(), spread_disc.std()
        if spread_std < 1e-9:
            continue

        spread_val = (prices_validation[a] - hedge_ratio * prices_validation[b]).dropna()
        z_val = (spread_val - spread_mean) / spread_std
        signals = z_val[z_val.abs() > Z_ENTRY_THRESHOLD]
        if len(signals) < 5:
            continue

        reverted, checked = 0, 0
        z_values = z_val.values
        z_index = list(z_val.index)
        for sig_date in signals.index:
            i = z_index.index(sig_date)
            if i + FORWARD_DAYS >= len(z_values):
                continue
            checked += 1
            if abs(z_values[i + FORWARD_DAYS]) < abs(z_values[i]):
                reverted += 1

        if checked < 5:
            continue
        reverted_pct = reverted / checked * 100
        results.append((a, b, pvalue, checked, reverted_pct))
    return results


def run():
    symbols = get_symbols()
    prices = download_price_history(symbols)
    cutoff_idx = int(len(prices) * 0.6)

    # SWAPPED: the later block (originally "out-of-sample") now does discovery; the earlier
    # block (originally "in-sample") now validates. Same boundary, reversed roles.
    prices_discovery = prices.iloc[cutoff_idx:]
    prices_validation = prices.iloc[:cutoff_idx]
    print(f"Discovery period (swapped):  {prices_discovery.index.min().date()} to {prices_discovery.index.max().date()} ({len(prices_discovery)} days)")
    print(f"Validation period (swapped): {prices_validation.index.min().date()} to {prices_validation.index.max().date()} ({len(prices_validation)} days)")

    candidates = find_cointegrated_pairs(prices_discovery, symbols)
    results = validate(candidates, prices_discovery, prices_validation)

    print(f"\n{'=' * 70}")
    real_pairs = [r for r in results if r[4] > 55]
    print(f"Of {len(candidates)} discovery-period candidates, {len(results)} had enough signals to test.")
    print(f"{len(real_pairs)} showed >55% reversion in the (swapped) validation period.\n")

    asianpaint_count = sum(1 for r in real_pairs if "ASIANPAINT" in (r[0], r[1]))
    print(f"ASIANPAINT appears in {asianpaint_count}/{len(real_pairs)} of the swapped-test winners "
          f"(compare to ~30/42 in the original split).\n")

    print("Checking the 3 pairs flagged as economically plausible from the original test:")
    for a, b in WATCH_PAIRS:
        match = next((r for r in results if {r[0], r[1]} == {a, b}), None)
        found_as_candidate = any({c[0], c[1]} == {a, b} for c in candidates)
        if match:
            _, _, pvalue, checked, reverted_pct = match
            print(f"  {a}/{b}: appeared as candidate (p={pvalue:.6f}), "
                  f"{reverted_pct:.1f}% reversion over {checked} swapped-validation signals "
                  f"-> {'HOLDS UP' if reverted_pct > 55 else 'does NOT hold up'}")
        elif found_as_candidate:
            print(f"  {a}/{b}: was a discovery-period candidate but too few validation signals to score")
        else:
            print(f"  {a}/{b}: did NOT even pass the discovery-period cointegration screen this time -> does NOT hold up")

    if real_pairs:
        print("\nAll swapped-test winners (>55% reversion):")
        for a, b, pvalue, checked, reverted_pct in sorted(real_pairs, key=lambda r: -r[4]):
            print(f"  {a}/{b}: {reverted_pct:.1f}% over {checked} signals (discovery p={pvalue:.6f})")


if __name__ == "__main__":
    run()
