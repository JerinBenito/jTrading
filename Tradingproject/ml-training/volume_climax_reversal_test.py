"""Does a big price move ACCOMPANIED BY unusually high volume reverse more than a similar-sized
move on normal/low volume? This is the real, published finding (Lee & Swaminathan 2000): high-
volume winners/losers reverse faster and stronger. Testing it on our own basket, at the
20-day and 40-day horizons (closer to what the literature actually studied than a 5-10 day test).
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

    df = df.dropna(subset=["dailyReturnPct", "volumeRatio20d", "forwardReturn20dPct", "forwardReturn40dPct"])
    # Normalize "big move" per stock (each stock has its own typical volatility) - top quartile
    # of |dailyReturnPct| WITHIN that stock's own history.
    df["absMove"] = df["dailyReturnPct"].abs()
    df["moveRankWithinStock"] = df.groupby("instrument")["absMove"].rank(pct=True)
    big_moves = df[df["moveRankWithinStock"] >= 0.75].copy()
    print(f"Big-move days (top quartile move size, per stock): {len(big_moves)}")

    big_moves["volRankWithinStock"] = df.groupby("instrument")["volumeRatio20d"].rank(pct=True).loc[big_moves.index]
    high_vol = big_moves[big_moves["volRankWithinStock"] >= 0.5]
    low_vol = big_moves[big_moves["volRankWithinStock"] < 0.5]
    print(f"  high-volume big moves: {len(high_vol)}, low-volume big moves: {len(low_vol)}")

    for horizon_col, label in [("forwardReturn20dPct", "20d"), ("forwardReturn40dPct", "40d")]:
        # Reversal metric: -sign(today's move) * forward return. Positive = real reversal,
        # negative = continuation.
        for name, group in [("HIGH volume", high_vol), ("LOW volume", low_vol)]:
            reversal = -np.sign(group["dailyReturnPct"]) * group[horizon_col]
            t_stat, p = stats.ttest_1samp(reversal, 0)
            print(f"  [{label}] {name} big moves: n={len(group):4d}  mean reversal={reversal.mean():+.3f}%  "
                  f"t-stat={t_stat:+.2f}  p={p:.4f}")
        # Direct comparison: is HIGH volume's reversal significantly bigger than LOW volume's?
        rev_high = -np.sign(high_vol["dailyReturnPct"]) * high_vol[horizon_col]
        rev_low = -np.sign(low_vol["dailyReturnPct"]) * low_vol[horizon_col]
        t2, p2 = stats.ttest_ind(rev_high, rev_low)
        print(f"  [{label}] HIGH vs LOW volume reversal difference: t-stat={t2:+.2f}  p={p2:.4f}\n")
