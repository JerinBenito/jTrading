"""Does a stock's cross-sectional relative-volume rank persist day to day? I.e. if HCLTECH was
among the most heavily-traded basket stocks yesterday, is it more likely to be among the most
heavily-traded again today (volume clustering), or is it essentially random day to day?
"""
import json
import urllib.request as u
import numpy as np
import pandas as pd
from scipy import stats

API_BASE = "https://jerintradingsignal.duckdns.org"


def get_json(path):
    with u.urlopen(f"{API_BASE}{path}", timeout=120) as resp:
        return json.loads(resp.read())


if __name__ == "__main__":
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
    df = df.dropna(subset=["volumeRatio20d"])
    df["rankPct"] = df.groupby("tradingDate")["volumeRatio20d"].rank(pct=True)
    df = df.sort_values(["instrument", "tradingDate"])

    # Lag-1: yesterday's rank vs today's rank, per stock, then pooled.
    df["prevRankPct"] = df.groupby("instrument")["rankPct"].shift(1)
    valid = df.dropna(subset=["prevRankPct", "rankPct"])
    print(f"n={len(valid)} (stock, day) pairs with both today's and yesterday's rank")

    ic, p = stats.spearmanr(valid["prevRankPct"], valid["rankPct"])
    n = len(valid)
    t_stat = ic / np.sqrt((1 - ic**2) / (n - 2))
    print(f"\nDoes yesterday's relative-volume rank predict today's? Spearman={ic:+.4f}  t-stat={t_stat:+.2f}  p={p:.6f}")

    # Concrete, interpretable version: was a stock in the top 20% yesterday more likely to be
    # in the top 20% again today, vs the unconditional base rate (20%)?
    valid["top20_yesterday"] = valid["prevRankPct"] >= 0.8
    valid["top20_today"] = valid["rankPct"] >= 0.8
    among_top_yesterday = valid[valid["top20_yesterday"]]
    rate = among_top_yesterday["top20_today"].mean() * 100
    baseline = valid["top20_today"].mean() * 100
    print(f"\nOf stocks in the top 20% by relative volume yesterday, {rate:.1f}% were still in the top 20% today")
    print(f"(baseline / unconditional rate of being in the top 20% on any given day: {baseline:.1f}%)")
