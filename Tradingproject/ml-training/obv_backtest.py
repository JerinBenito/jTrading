"""On-Balance Volume (Granville, 1963): cumulative running total of volume, added on up-close
days and subtracted on down-close days - the user's own "if he bought and didn't sell yet, that
pressure is still pending" logic, using price direction as the only available buy/sell proxy
(plain candle data can't know who actually bought vs sold).

Raw OBV isn't comparable across stocks (unbounded cumulative scale), so the testable, standard
form is OBV's own recent momentum (10-day rate of change) - does that predict forward returns?
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
    all_df = pd.concat(frames, ignore_index=True)
    all_df["tradingDate"] = pd.to_datetime(all_df["tradingDate"])
    all_df = all_df.sort_values(["instrument", "tradingDate"])

    results = []
    for inst, g in all_df.groupby("instrument"):
        g = g.dropna(subset=["volume", "dailyReturnPct"]).copy()
        if len(g) < 30:
            continue
        signed_vol = np.where(g["dailyReturnPct"] > 0, g["volume"], np.where(g["dailyReturnPct"] < 0, -g["volume"], 0))
        g["obv"] = np.cumsum(signed_vol)
        g["obvMomentum10d"] = g["obv"].pct_change(10)
        results.append(g[["instrument", "tradingDate", "obvMomentum10d", "forwardReturn5dPct", "forwardReturn10dPct", "forwardReturn20dPct"]])

    df = pd.concat(results, ignore_index=True)
    df = df.replace([np.inf, -np.inf], np.nan)

    for target in ["forwardReturn5dPct", "forwardReturn10dPct", "forwardReturn20dPct"]:
        rows = df.dropna(subset=["obvMomentum10d", target])
        if len(rows) < 30:
            print(f"{target}: too few rows")
            continue
        ic, p = stats.spearmanr(rows["obvMomentum10d"], rows[target])
        n = len(rows)
        t_stat = ic / np.sqrt((1 - ic**2) / (n - 2))
        print(f"OBV 10-day momentum -> {target}: n={n}  Spearman={ic:+.4f}  t-stat={t_stat:+.2f}  p={p:.4f}")
