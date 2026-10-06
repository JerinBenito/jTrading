"""Downloads NSE's daily security-wise delivery position files (sec_bhavdata_full_DDMMYYYY.csv from
nsearchives.nseindia.com - plain HTTP, no cookies, years of history) for every trading day we
have data for, keeps only the 50 basket stocks (EQ series), and writes one compact CSV.

Columns of interest: TTL_TRD_QNTY (shares traded), NO_OF_TRADES, DELIV_QTY (shares actually
delivered, i.e. not squared off intraday), DELIV_PER (delivered / traded, %).
Usage: python nse_delivery_fetch.py [output.csv]
"""
import io
import json
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import pandas as pd

API = "https://jerintradingsignal.duckdns.org"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
ALIASES = {"TATAMOTORS": "TMPV"}  # Tata Motors passenger vehicles demerger rename in our basket


def get_json(path):
    with urllib.request.urlopen(f"{API}{path}", timeout=60) as r:
        return json.loads(r.read())


def fetch_day(date_iso):
    y, m, d = date_iso.split("-")
    url = f"https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_{d}{m}{y}.csv"
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=40) as r:
                return date_iso, r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return date_iso, None  # holiday / no file
        except Exception:
            pass
        time.sleep(2 * (attempt + 1))
    return date_iso, b""  # persistent failure


def main(out_path):
    basket = [r["symbol"] for r in get_json("/api/monitor/basket") if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
    wanted = set(basket) | set(ALIASES)
    dates = [r["tradingDate"] for r in get_json("/api/ml/features/NIFTY")]
    print(f"{len(dates)} trading dates {dates[0]}..{dates[-1]}, {len(basket)} basket symbols", flush=True)

    frames, missing, failed = [], [], []
    with ThreadPoolExecutor(max_workers=4) as ex:
        for i, (d, raw) in enumerate(ex.map(fetch_day, dates), 1):
            if raw is None:
                missing.append(d)
            elif raw == b"":
                failed.append(d)
            else:
                df = pd.read_csv(io.BytesIO(raw), skipinitialspace=True)
                df.columns = [c.strip() for c in df.columns]
                df["SERIES"] = df["SERIES"].astype(str).str.strip()
                df["SYMBOL"] = df["SYMBOL"].astype(str).str.strip()
                df = df[(df["SERIES"] == "EQ") & df["SYMBOL"].isin(wanted)].copy()
                df["SYMBOL"] = df["SYMBOL"].replace(ALIASES)
                df["tradingDate"] = d
                frames.append(df)
            if i % 100 == 0:
                print(f"  {i}/{len(dates)} done", flush=True)
    out = pd.concat(frames, ignore_index=True)
    for c in ["TTL_TRD_QNTY", "NO_OF_TRADES", "DELIV_QTY", "DELIV_PER", "CLOSE_PRICE", "OPEN_PRICE"]:
        out[c] = pd.to_numeric(out[c], errors="coerce")
    out.to_csv(out_path, index=False)
    print(f"wrote {len(out)} rows, {out['SYMBOL'].nunique()} symbols, {out['tradingDate'].nunique()} days -> {out_path}")
    print(f"no file (holiday/404): {len(missing)} dates; hard failures: {len(failed)} {failed[:5]}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "nse_delivery_basket.csv")
