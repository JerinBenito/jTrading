"""Does India VIX or FII/DII net flow predict NIFTY's OWN next-N-day return?

Not a cross-sectional ranking test (VIX/FII-DII are the same number for every stock on a given
day, so they carry zero cross-sectional ranking power) - this tests market TIMING instead: does
today's VIX level/change, or today's institutional net flow, predict where NIFTY itself goes over
the following days. Same honest statistical bar as every other test this session: real
correlation, real t-stat, no cherry-picking.
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


def load_nifty():
    rows = get_json("/api/ml/features/NIFTY")
    df = pd.DataFrame(rows)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    return df[["tradingDate", "dailyReturnPct", "forwardReturn5dPct", "forwardReturn10dPct"]].sort_values("tradingDate")


def load_vix():
    data = get_json("/v8/finance/chart/%5EINDIAVIX?range=2y&interval=1d", base="https://query1.finance.yahoo.com",
                     headers={"User-Agent": "Mozilla/5.0"})
    result = data["chart"]["result"][0]
    ts = result["timestamp"]
    closes = result["indicators"]["quote"][0]["close"]
    df = pd.DataFrame({"tradingDate": pd.to_datetime(ts, unit="s").tz_localize(None).normalize(), "vix": closes})
    df = df.dropna(subset=["vix"])
    df["vix_change_pct"] = df["vix"].pct_change() * 100
    return df


def load_fii_dii():
    rows = get_json("/api/history-full", base="https://fii-diidata.mrchartist.com")
    df = pd.DataFrame(rows)
    df["tradingDate"] = pd.to_datetime(df["d"], format="%d-%b-%Y")
    df = df.rename(columns={"fn": "fii_net", "dn": "dii_net"})
    return df[["tradingDate", "fii_net", "dii_net"]].sort_values("tradingDate")


def report_predictive_power(df, feature, target, label):
    rows = df.dropna(subset=[feature, target])
    if len(rows) < 30:
        print(f"  {label:35s}: only {len(rows)} overlapping days - too few to test")
        return
    ic, p = stats.spearmanr(rows[feature], rows[target])
    n = len(rows)
    t_stat = ic / np.sqrt((1 - ic**2) / (n - 2)) if abs(ic) < 1 else np.nan
    print(f"  {label:35s}: n={n:4d}  Spearman={ic:+.4f}  t-stat={t_stat:+.2f}  p={p:.4f}")


if __name__ == "__main__":
    nifty = load_nifty()
    print(f"NIFTY feature history: {len(nifty)} rows, {nifty['tradingDate'].min().date()} to {nifty['tradingDate'].max().date()}")

    vix = load_vix()
    print(f"India VIX (Yahoo Finance): {len(vix)} rows, {vix['tradingDate'].min().date()} to {vix['tradingDate'].max().date()}")

    fiidii = load_fii_dii()
    print(f"FII/DII (mrchartist.com): {len(fiidii)} rows, {fiidii['tradingDate'].min().date()} to {fiidii['tradingDate'].max().date()}")

    merged_vix = nifty.merge(vix, on="tradingDate", how="inner")
    merged_flow = nifty.merge(fiidii, on="tradingDate", how="inner")

    print(f"\n=== Does India VIX predict NIFTY's own forward return? (n={len(merged_vix)} overlapping days) ===")
    report_predictive_power(merged_vix, "vix", "forwardReturn5dPct", "VIX level -> fwd 5d NIFTY return")
    report_predictive_power(merged_vix, "vix", "forwardReturn10dPct", "VIX level -> fwd 10d NIFTY return")
    report_predictive_power(merged_vix, "vix_change_pct", "forwardReturn5dPct", "VIX daily change -> fwd 5d NIFTY return")
    report_predictive_power(merged_vix, "vix_change_pct", "forwardReturn10dPct", "VIX daily change -> fwd 10d NIFTY return")

    print(f"\n=== Does FII/DII net flow predict NIFTY's own forward return? (n={len(merged_flow)} overlapping days) ===")
    report_predictive_power(merged_flow, "fii_net", "forwardReturn5dPct", "FII net flow -> fwd 5d NIFTY return")
    report_predictive_power(merged_flow, "fii_net", "forwardReturn10dPct", "FII net flow -> fwd 10d NIFTY return")
    report_predictive_power(merged_flow, "dii_net", "forwardReturn5dPct", "DII net flow -> fwd 5d NIFTY return")
    report_predictive_power(merged_flow, "dii_net", "forwardReturn10dPct", "DII net flow -> fwd 10d NIFTY return")
