"""Does a risk-managed exit rule (profit target / stop loss / max hold) create positive
expectancy on its own, with ZERO predictive signal - no ranking, no model, just enter every
basket stock on every single trading day and let the exit rule do all the work?

This directly tests the claim that a trader's real edge often comes from risk management, not
from prediction accuracy. If real stock return distributions have enough positive skew (occasional
big rallies, capped downside from a stop), a pure risk-managed exit could show positive expectancy
even with no signal at all. If this comes back flat too, that closes off "risk management alone
saves it" as an explanation.
"""
import json
import urllib.request as u
import numpy as np
import pandas as pd
from scipy import stats

API_BASE = "https://jerintradingsignal.duckdns.org"


def get_json(path):
    with u.urlopen(f"{API_BASE}{path}", timeout=60) as resp:
        return json.loads(resp.read())


def load_all(symbols):
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


def simulate(price_history, profit_targets, stop_losses, max_hold_days):
    by_instrument = {inst: g.sort_values("tradingDate").reset_index(drop=True) for inst, g in price_history.groupby("instrument")}
    results = []
    for pt in profit_targets:
        for sl in stop_losses:
            for hold in max_hold_days:
                trades = []
                for inst, hist in by_instrument.items():
                    n = len(hist)
                    # Enter EVERY day (subsampled every 3rd day to keep trades roughly
                    # independent - overlapping daily entries with a multi-day hold are not
                    # independent observations and would inflate n artificially).
                    for i in range(0, n - hold - 1, 3):
                        entry_price = hist.loc[i, "open"]
                        if pd.isna(entry_price) or float(entry_price) == 0:
                            continue
                        entry_price = float(entry_price)
                        target_price = entry_price * (1 + pt / 100)
                        stop_price = entry_price * (1 - sl / 100)
                        outcome, ret = None, None
                        for h in range(1, hold + 1):
                            j = i + h
                            if j >= n:
                                break
                            high = hist.loc[j, "high"]
                            low = hist.loc[j, "low"]
                            if pd.isna(high) or pd.isna(low):
                                continue
                            high, low = float(high), float(low)
                            hit_stop = low <= stop_price
                            hit_target = high >= target_price
                            if hit_stop and hit_target:
                                outcome, ret = "stop", -sl
                                break
                            if hit_stop:
                                outcome, ret = "stop", -sl
                                break
                            if hit_target:
                                outcome, ret = "target", pt
                                break
                        if outcome is None:
                            j = min(i + hold, n - 1)
                            exitp = hist.loc[j, "close"]
                            if pd.isna(exitp):
                                continue
                            outcome = "timeout"
                            ret = (float(exitp) - entry_price) / entry_price * 100
                        trades.append(ret)
                if len(trades) < 30:
                    continue
                arr = np.array(trades)
                t_stat, p = stats.ttest_1samp(arr, 0)
                results.append(dict(pt=pt, sl=sl, hold=hold, n=len(arr), mean=arr.mean(),
                                     win_rate=(arr > 0).mean() * 100, t_stat=t_stat, p=p))
    return pd.DataFrame(results)


if __name__ == "__main__":
    symbols = [r["symbol"] for r in get_json("/api/monitor/basket") if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
    prices = load_all(symbols)
    print(f"Loaded {prices['instrument'].nunique()} instruments, {len(prices)} rows, "
          f"{prices['tradingDate'].min().date()} to {prices['tradingDate'].max().date()}")

    results = simulate(prices, profit_targets=[1.5, 2.0, 3.0, 5.0], stop_losses=[1.0, 1.5, 2.0], max_hold_days=[3, 5, 10, 20])
    results = results.sort_values("t_stat", ascending=False)
    print(f"\n{len(results)} parameter combinations tested, sorted by t-stat (best first):\n")
    print(results.to_string(index=False, float_format=lambda x: f"{x:.3f}"))

    print(f"\nHow many combinations clear |t|>2 (real, uncorrected significance): {(results['t_stat'].abs() > 2).sum()} of {len(results)}")
    print(f"After Bonferroni correction across {len(results)} tests, needed p < {0.05/len(results):.5f}")
    print(f"How many survive that: {(results['p'] < 0.05/len(results)).sum()}")
