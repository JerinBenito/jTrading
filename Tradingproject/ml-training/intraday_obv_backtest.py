"""Does growing volume WITHIN the day (hour by hour), combined with the direction it's growing
in, predict where THAT SAME DAY's close will land? The intraday version of the OBV idea -
returnSoFarPct (direction since open) x volumeSoFarRatio (how busy today has been so far) as the
interaction, tested against remainingDriftPct (the rest of the day's actual move) at several
real checkpoints through the session.
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
    rows = get_json("/api/ml/intraday-features/all")
    df = pd.DataFrame(rows)
    df = df[~df["instrument"].isin(["NIFTY", "BANKNIFTY"])]
    df["intradayObv"] = pd.to_numeric(df["returnSoFarPct"], errors="coerce") * \
        pd.to_numeric(df["volumeSoFarRatio"], errors="coerce")

    for hour in sorted(df["hoursSinceOpen"].unique()):
        sub = df[df["hoursSinceOpen"] == hour].dropna(subset=["intradayObv", "remainingDriftPct"])
        if len(sub) < 100:
            continue
        ic, p = stats.spearmanr(sub["intradayObv"], sub["remainingDriftPct"])
        n = len(sub)
        t_stat = ic / np.sqrt((1 - ic**2) / (n - 2))
        print(f"hour {hour}: n={n:5d}  intraday-OBV -> remaining drift: Spearman={ic:+.4f}  t-stat={t_stat:+.2f}  p={p:.4f}")
