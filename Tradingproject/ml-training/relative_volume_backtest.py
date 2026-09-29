"""Cross-sectional relative volume, tested at the first hour specifically (per the user's own
framing): among the 50 basket stocks, which ones are unusually busy TODAY compared to how busy
the OTHERS are today (not just compared to their own history)? And among the unusually busy
ones, does the first hour's own direction (as a buy-pressure/sell-pressure proxy - real
buy/sell-initiated volume isn't available from plain candles, only order-book data, which is
too new to test) predict whether the rest of the day continues in that direction?

Two honest, separate questions, tested in order:
1. Rank IC: does a high cross-sectional relative-volume rank at hour 1 predict a BIGGER
   remaining move that day (in either direction) - i.e. does relative volume identify which
   stocks are "in play" today?
2. Among the high-relative-volume stocks, does the first hour's own direction (up vs down)
   predict the SAME direction for the rest of the day (continuation), or the opposite
   (reversal), or nothing?
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


def load_all():
    rows = get_json("/api/ml/intraday-features/all")
    df = pd.DataFrame(rows)
    df = df[~df["instrument"].isin(["NIFTY", "BANKNIFTY"])]  # indices carry no real volume
    return df


if __name__ == "__main__":
    df = load_all()
    hour1 = df[df["hoursSinceOpen"] == 1].copy()
    hour1 = hour1.dropna(subset=["volumeSoFarRatio", "returnSoFarPct", "remainingDriftPct"])
    print(f"Hour-1 rows with real volume data: {len(hour1)}, "
          f"{hour1['tradingDate'].nunique()} trading days, {hour1['instrument'].nunique()} stocks")

    # Cross-sectional rank: percentile of volumeSoFarRatio WITHIN each day's basket, not vs own history.
    hour1["volRankPct"] = hour1.groupby("tradingDate")["volumeSoFarRatio"].rank(pct=True)
    hour1["absRemainingDrift"] = hour1["remainingDriftPct"].abs()

    print("\n=== Q1: does high cross-sectional relative volume at hour 1 predict a BIGGER remaining move (either direction)? ===")
    daily_ics = []
    for day, g in hour1.groupby("tradingDate"):
        if len(g) < 15:
            continue
        ic, _ = stats.spearmanr(g["volRankPct"], g["absRemainingDrift"])
        if not np.isnan(ic):
            daily_ics.append(ic)
    ics = np.array(daily_ics)
    t_stat = ics.mean() / (ics.std() / np.sqrt(len(ics))) if ics.std() > 0 else 0
    print(f"  n_days={len(ics)}  mean Rank IC={ics.mean():+.4f}  t-stat={t_stat:+.2f}")

    print("\n=== Q2: among the top-volume-rank stocks each day, does hour-1 direction predict the rest of the day? ===")
    for pct_cutoff, label in [(0.8, "top 20% relative volume"), (0.9, "top 10% relative volume")]:
        top = hour1[hour1["volRankPct"] >= pct_cutoff].copy()
        top["hour1_up"] = top["returnSoFarPct"] > 0
        continuation = ((top["hour1_up"]) & (top["remainingDriftPct"] > 0)) | \
                       ((~top["hour1_up"]) & (top["remainingDriftPct"] < 0))
        n = len(top)
        cont_rate = continuation.mean() * 100
        # binomial test against a fair 50/50 coin
        p_val = stats.binomtest(int(continuation.sum()), n, 0.5).pvalue
        mean_ret_if_up = top[top["hour1_up"]]["remainingDriftPct"].mean()
        mean_ret_if_down = top[~top["hour1_up"]]["remainingDriftPct"].mean()
        print(f"  {label}: n={n}  continuation rate={cont_rate:.1f}% (vs 50% baseline)  p={p_val:.4f}")
        print(f"    avg remaining drift when hour-1 was UP: {mean_ret_if_up:+.3f}%   when hour-1 was DOWN: {mean_ret_if_down:+.3f}%")

    print("\n=== For comparison: same test, LOW relative volume (bottom 20%) ===")
    bottom = hour1[hour1["volRankPct"] <= 0.2].copy()
    bottom["hour1_up"] = bottom["returnSoFarPct"] > 0
    continuation = ((bottom["hour1_up"]) & (bottom["remainingDriftPct"] > 0)) | \
                   ((~bottom["hour1_up"]) & (bottom["remainingDriftPct"] < 0))
    n = len(bottom)
    p_val = stats.binomtest(int(continuation.sum()), n, 0.5).pvalue
    print(f"  bottom 20% relative volume: n={n}  continuation rate={continuation.mean()*100:.1f}%  p={p_val:.4f}")
