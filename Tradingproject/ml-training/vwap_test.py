"""Intraday VWAP / early-momentum tests on 1-minute data (basket, Jan 2022 -> now). At each decision minute (10:15, 10:30,
11:15) take every stock's price vs its own volume-weighted average price since the open, and its return since the open; do
those predict the return from that minute to the 15:15 close? Cross-sectional (per-day Spearman IC across stocks; day is the
unit) and as a long-top-/short-bottom-quintile spread net of a 0.10% round-trip cost. 3 decision times x 2 signals = 6 looks
(Bonferroni |t| ~ 2.6). Usage: python vwap_test.py
"""
import numpy as np
import pandas as pd
from scipy import stats

import minute_store as ms

DECISIONS = {"10:15": 60, "10:30": 75, "11:15": 120}
EOD = 360
COST = 0.10


def stock_rows(sym):
    d = ms.load_minute(sym)
    out = []
    for day, g in d.groupby("day"):
        if len(g) != 375:
            continue
        o, h, l, c, v = (g[k].to_numpy(float) for k in ("open", "high", "low", "close", "volume"))
        tp = (h + l + c) / 3
        cumv = np.cumsum(v)
        cumpv = np.cumsum(tp * v)
        for label, i in DECISIONS.items():
            if cumv[i] <= 0:
                continue
            vwap = cumpv[i] / cumv[i]
            out.append((sym, day, label, (c[i] / vwap - 1) * 100, (c[i] / o[0] - 1) * 100, (c[EOD] / c[i] - 1) * 100))
    return out


if __name__ == "__main__":
    rows = []
    for s in [x for x in ms.symbols() if x not in ("NIFTY", "BANKNIFTY")]:
        rows += stock_rows(s)
    r = pd.DataFrame(rows, columns=["sym", "day", "when", "vs_vwap", "since_open", "fwd"])
    print(f"{len(r):,} stock-day-decisions, {r.day.nunique()} days\n")
    print(f"{'decision':8s} {'signal':12s} {'mean daily rank IC':>19s} {'t':>7s} {'Q-spread gross%':>16s} {'net@0.10':>9s} {'day t(net)':>11s}")
    for at in DECISIONS:
        x = r[r["when"] == at]
        for sig in ("vs_vwap", "since_open"):
            ic = x.groupby("day").apply(lambda g: stats.spearmanr(g[sig], g.fwd)[0] if len(g) > 20 else np.nan).dropna()
            def spread(g):
                k = max(3, len(g) // 5)
                s = g.sort_values(sig)
                return s.tail(k).fwd.mean() - s.head(k).fwd.mean()        # long top quintile, short bottom: return of 2 legs / 1 unit gross
            sp = x.groupby("day").apply(spread)
            net = sp / 2 - COST                                          # 1 unit of capital split over two legs, one round trip each
            t = lambda a: a.mean() / (a.std(ddof=1) / np.sqrt(len(a)))
            print(f"{at:8s} {sig:12s} {ic.mean():+19.4f} {t(ic):+7.2f} {sp.mean() / 2:+16.3f} {net.mean():+9.3f} {t(net):+11.2f}")
    print("\n(positive IC = continuation; negative = reversal back toward the average)")
