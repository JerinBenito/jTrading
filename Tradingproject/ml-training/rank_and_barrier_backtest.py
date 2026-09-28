"""Cross-sectional ranking + triple-barrier backtest.

Two honest questions, answered in order, and NOT skipped past if the first one fails:

1. Does ranking the basket by a feature known BEFORE the day actually correlate with which
   stocks outperformed afterward (Rank IC / Spearman correlation)? Every candidate feature used
   here is already sitting in ml_feature_snapshots as of that day's close - no lookahead.

2. Only if (1) shows real, non-trivial skill: for the top-N ranked stocks each day, simulate a
   triple-barrier exit (profit target / stop-loss / max holding days) across several parameter
   combinations, walking forward day-by-day through REAL subsequent daily bars (open/high/low/
   close), and report the real historical win rate and average return per combination.

Uses daily bars only (see run notes) - if a day's high clears the target AND its low breaches
the stop on the same day, the stop is counted as hit first (conservative; the exact intraday
order isn't knowable without hourly candles).
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


def get_symbols():
    return [row["symbol"] for row in get_json("/api/monitor/basket")]


def load_all(symbols):
    frames = []
    for s in symbols:
        rows = get_json(f"/api/ml/features/{s}")
        if not rows:
            continue
        df = pd.DataFrame(rows)
        df["instrument"] = s
        frames.append(df)
    all_df = pd.concat(frames, ignore_index=True)
    all_df["tradingDate"] = pd.to_datetime(all_df["tradingDate"])
    # NIFTY/BANKNIFTY are indices, not tradeable single names for this exercise - basket stocks only.
    all_df = all_df[~all_df["instrument"].isin(["NIFTY", "BANKNIFTY"])]
    return all_df.sort_values(["instrument", "tradingDate"]).reset_index(drop=True)


CANDIDATE_FEATURES = {
    "return5dPct": "5-day momentum",
    "return10dPct": "10-day momentum",
    "return20dPct": "20-day momentum",
    "rsi14": "RSI(14)",
    "emaSpreadPct": "EMA9/21 spread",
    "dailyReturnPct": "yesterday's own return (short-term reversal candidate)",
}
FORWARD_TARGET = "forwardReturn5dPct"  # already computed server-side, no lookahead risk


def rank_ic_report(df):
    print("=== 1. Rank IC: does ranking by each feature predict forward 5-day relative return? ===")
    results = {}
    for feat, label in CANDIDATE_FEATURES.items():
        rows = df.dropna(subset=[feat, FORWARD_TARGET])
        ics = []
        for day, group in rows.groupby("tradingDate"):
            if len(group) < 10:
                continue
            if group[feat].nunique() < 5:
                continue
            ic, _ = stats.spearmanr(group[feat], group[FORWARD_TARGET])
            if not np.isnan(ic):
                ics.append(ic)
        if not ics:
            print(f"  {label:45s}: no valid days")
            continue
        mean_ic = np.mean(ics)
        std_ic = np.std(ics)
        icir = mean_ic / std_ic if std_ic > 0 else 0
        # rough t-stat treating each day's IC as one observation
        t_stat = mean_ic / (std_ic / np.sqrt(len(ics))) if std_ic > 0 else 0
        results[feat] = dict(mean_ic=mean_ic, icir=icir, n_days=len(ics), t_stat=t_stat)
        print(f"  {label:45s}: mean Rank IC={mean_ic:+.4f}  ICIR={icir:+.3f}  n_days={len(ics):4d}  t-stat={t_stat:+.2f}")
    return results


def decile_spread_report(df, feat):
    print(f"\n=== 2. Decile spread using '{feat}': does top decile actually beat bottom decile? ===")
    rows = df.dropna(subset=[feat, FORWARD_TARGET])
    top_returns, bottom_returns = [], []
    for day, group in rows.groupby("tradingDate"):
        if len(group) < 20:
            continue
        g = group.sort_values(feat)
        n = len(g)
        decile = max(1, n // 10)
        bottom_returns.extend(g.iloc[:decile][FORWARD_TARGET].tolist())
        top_returns.extend(g.iloc[-decile:][FORWARD_TARGET].tolist())
    if not top_returns:
        print("  not enough days with >=20 stocks")
        return None
    top_mean, bottom_mean = np.mean(top_returns), np.mean(bottom_returns)
    spread = top_mean - bottom_mean
    t_stat, p_val = stats.ttest_ind(top_returns, bottom_returns)
    print(f"  top decile avg forward 5d return:    {top_mean:+.3f}%  (n={len(top_returns)})")
    print(f"  bottom decile avg forward 5d return: {bottom_mean:+.3f}%  (n={len(bottom_returns)})")
    print(f"  spread (top - bottom):               {spread:+.3f}%   t-stat={t_stat:+.2f}  p={p_val:.4f}")
    return dict(top_mean=top_mean, bottom_mean=bottom_mean, spread=spread, t_stat=t_stat, p_val=p_val)


def triple_barrier_backtest(df, feat, top_n=5, profit_targets=(1.5, 2.0, 3.0), stop_losses=(1.0, 1.5), max_hold_days=(3, 5, 10)):
    print(f"\n=== 3. Triple-barrier backtest: top-{top_n} ranked by '{feat}' each day, varying exit rules ===")
    by_instrument = {inst: g.sort_values("tradingDate").reset_index(drop=True) for inst, g in df.groupby("instrument")}
    dates = sorted(df["tradingDate"].unique())

    results = []
    for pt in profit_targets:
        for sl in stop_losses:
            for hold in max_hold_days:
                trades = []
                for day in dates:
                    day_rows = df[(df["tradingDate"] == day) & df[feat].notna()]
                    if len(day_rows) < 20:
                        continue
                    top = day_rows.sort_values(feat, ascending=False).head(top_n)
                    for _, row in top.iterrows():
                        inst = row["instrument"]
                        hist = by_instrument[inst]
                        idx = hist.index[hist["tradingDate"] == day]
                        if len(idx) == 0:
                            continue
                        i = idx[0]
                        if i + 1 >= len(hist):
                            continue
                        entry_price = float(hist.loc[i + 1, "open"])  # enter at next day's open
                        target_price = entry_price * (1 + pt / 100)
                        stop_price = entry_price * (1 - sl / 100)
                        outcome, ret = None, None
                        for h in range(1, hold + 1):
                            j = i + 1 + h
                            if j >= len(hist):
                                break
                            day_high = float(hist.loc[j, "high"])
                            day_low = float(hist.loc[j, "low"])
                            hit_stop = day_low <= stop_price
                            hit_target = day_high >= target_price
                            if hit_stop and hit_target:
                                outcome, ret = "stop(same-day-ambiguous)", -sl
                                break
                            if hit_stop:
                                outcome, ret = "stop", -sl
                                break
                            if hit_target:
                                outcome, ret = "target", pt
                                break
                        if outcome is None:
                            j = min(i + 1 + hold, len(hist) - 1)
                            exit_price = float(hist.loc[j, "close"])
                            outcome = "timeout"
                            ret = (exit_price - entry_price) / entry_price * 100
                        trades.append(dict(outcome=outcome, ret=ret))
                if not trades:
                    continue
                tdf = pd.DataFrame(trades)
                n = len(tdf)
                win_rate = (tdf["ret"] > 0).mean() * 100
                avg_ret = tdf["ret"].mean()
                target_rate = (tdf["outcome"] == "target").mean() * 100
                stop_rate = tdf["outcome"].str.startswith("stop").mean() * 100
                timeout_rate = (tdf["outcome"] == "timeout").mean() * 100
                results.append(dict(profit_target=pt, stop_loss=sl, max_hold=hold, n_trades=n,
                                     win_rate=win_rate, avg_return=avg_ret,
                                     target_rate=target_rate, stop_rate=stop_rate, timeout_rate=timeout_rate))
                print(f"  PT={pt:.1f}% SL={sl:.1f}% hold<={hold}d: n={n:5d}  win_rate={win_rate:5.1f}%  "
                      f"avg_ret={avg_ret:+.3f}%  target={target_rate:4.1f}% stop={stop_rate:4.1f}% timeout={timeout_rate:4.1f}%")
    return pd.DataFrame(results)


if __name__ == "__main__":
    symbols = get_symbols()
    print(f"Loaded basket: {len(symbols)} symbols")
    df = load_all(symbols)
    print(f"Total rows: {len(df)}, date range: {df['tradingDate'].min().date()} to {df['tradingDate'].max().date()}")
    print(f"Rows with forward 5d return already resolved: {df[FORWARD_TARGET].notna().sum()}")

    ic_results = rank_ic_report(df)
    best_feat = max(ic_results, key=lambda k: abs(ic_results[k]["mean_ic"])) if ic_results else None
    if best_feat:
        print(f"\nStrongest candidate by |mean Rank IC|: {best_feat} ({CANDIDATE_FEATURES[best_feat]})")
        spread = decile_spread_report(df, best_feat)
        if spread and abs(spread["t_stat"]) > 2:
            triple_barrier_backtest(df, best_feat)
        else:
            print("\nDecile spread not statistically significant (|t-stat| <= 2) - skipping the barrier "
                  "backtest, since building an exit-rule study on top of a ranking signal that hasn't "
                  "shown real skill would just be dressing up noise.")
