"""Does India VIX predict each BASKET STOCK's own forward return (not NIFTY's), pooled across
all 50 stocks and all days? VIX is the same value for every stock on a given day, so this is
NOT a cross-sectional ranking test (can't rank with a constant) - it tests whether the
market-wide VIX->recovery effect found for NIFTY shows up in individual stocks' own returns too,
since they're correlated with the index they belong to.
"""
import json
import urllib.request as u
import numpy as np
import pandas as pd
from scipy import stats

API_BASE = "https://jerintradingsignal.duckdns.org"


def get_json(url_or_path, base=API_BASE, headers=None):
    req = u.Request(f"{base}{url_or_path}" if base else url_or_path, headers=headers or {})
    with u.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def load_basket():
    symbols = [r["symbol"] for r in get_json("/api/monitor/basket") if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
    frames = []
    for s in symbols:
        rows = get_json(f"/api/ml/features/{s}")
        if rows:
            df = pd.DataFrame(rows)
            df["instrument"] = s
            frames.append(df)
    df = pd.concat(frames, ignore_index=True)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    return df[["instrument", "tradingDate", "forwardReturn5dPct", "forwardReturn10dPct"]]


def load_vix():
    data = get_json("/v8/finance/chart/%5EINDIAVIX?range=2y&interval=1d", base="https://query1.finance.yahoo.com",
                     headers={"User-Agent": "Mozilla/5.0"})
    result = data["chart"]["result"][0]
    ts = result["timestamp"]
    closes = result["indicators"]["quote"][0]["close"]
    df = pd.DataFrame({"tradingDate": pd.to_datetime(ts, unit="s").tz_localize(None).normalize(), "vix": closes})
    return df.dropna(subset=["vix"])


def report(df, target, label):
    rows = df.dropna(subset=["vix", target])
    ic, p = stats.spearmanr(rows["vix"], rows[target])
    n = len(rows)
    t_stat = ic / np.sqrt((1 - ic**2) / (n - 2))
    n_stocks = rows["instrument"].nunique()
    n_days = rows["tradingDate"].nunique()
    print(f"  {label:35s}: n={n:6d} ({n_stocks} stocks x {n_days} days)  Spearman={ic:+.4f}  t-stat={t_stat:+.2f}  p={p:.4f}")


def per_stock_breakdown(df, target):
    print(f"\n  Per-stock breakdown ({target}), how many individually show a significant positive VIX relationship:")
    sig_count, pos_count, total = 0, 0, 0
    for inst, g in df.groupby("instrument"):
        rows = g.dropna(subset=["vix", target])
        if len(rows) < 100:
            continue
        ic, p = stats.spearmanr(rows["vix"], rows[target])
        total += 1
        if ic > 0:
            pos_count += 1
        if p < 0.05:
            sig_count += 1
    print(f"  {pos_count}/{total} stocks show a positive correlation, {sig_count}/{total} individually significant at p<0.05")


if __name__ == "__main__":
    basket = load_basket()
    vix = load_vix()
    print(f"Basket: {basket['instrument'].nunique()} stocks, {len(basket)} rows")
    print(f"VIX: {len(vix)} days, {vix['tradingDate'].min().date()} to {vix['tradingDate'].max().date()}")

    merged = basket.merge(vix, on="tradingDate", how="inner")

    print(f"\n=== Does VIX predict individual basket stocks' OWN forward return (pooled, 50 stocks)? ===")
    report(merged, "forwardReturn5dPct", "VIX level -> fwd 5d stock return")
    report(merged, "forwardReturn10dPct", "VIX level -> fwd 10d stock return")

    per_stock_breakdown(merged, "forwardReturn10dPct")
