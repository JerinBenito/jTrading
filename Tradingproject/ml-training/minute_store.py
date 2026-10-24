"""Loader for the locally stored 1-minute candles (download_minute_candles.py): data/minute/<SYMBOL>/<YYYY-MM>.csv.gz."""
import glob
import os

import pandas as pd

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "minute")


def symbols():
    return sorted(d for d in os.listdir(ROOT) if os.path.isdir(os.path.join(ROOT, d)))


def load_minute(symbol):
    """All stored 1-minute candles for one symbol, ascending, with IST 'dt' and 'day' columns."""
    files = sorted(glob.glob(os.path.join(ROOT, symbol, "*.csv.gz")))
    parts = [pd.read_csv(f) for f in files]
    parts = [p for p in parts if len(p)]
    if not parts:
        return pd.DataFrame(columns=["ts", "open", "high", "low", "close", "volume", "dt", "day"])
    d = pd.concat(parts, ignore_index=True).sort_values("ts").drop_duplicates("ts").reset_index(drop=True)
    d["dt"] = pd.to_datetime(d["ts"], unit="s", utc=True).dt.tz_convert("Asia/Kolkata")
    d["day"] = d["dt"].dt.date
    return d
