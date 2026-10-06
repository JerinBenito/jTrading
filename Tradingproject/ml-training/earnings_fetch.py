"""Fetch historical quarterly results dates + EPS estimate/actual/surprise for the 50 basket stocks
from Yahoo (yfinance), write one CSV. Usage: python earnings_fetch.py out.csv"""
import sys, time, json, urllib.request
import pandas as pd
import yfinance as yf

API = "https://jerintradingsignal.duckdns.org"
basket = [r["symbol"] for r in json.loads(urllib.request.urlopen(f"{API}/api/monitor/basket", timeout=60).read())
          if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
rows, failed = [], []
for s in basket:
    got = None
    for yahoo in (s, "TMPV") if s == "TATAMOTORS" else (s,):
        for attempt in range(3):
            try:
                ed = yf.Ticker(f"{yahoo.replace('&', '%26')}.NS").get_earnings_dates(limit=16)
                if ed is not None and len(ed):
                    got = ed; break
            except Exception as e:
                time.sleep(2 * (attempt + 1))
        if got is not None: break
    if got is None:
        failed.append(s); print("FAILED", s, flush=True); continue
    got = got.reset_index()
    got["instrument"] = s
    rows.append(got); print(f"{s}: {len(got)} dates", flush=True)
    time.sleep(0.7)
out = pd.concat(rows, ignore_index=True)
out.to_csv(sys.argv[1], index=False)
print(f"wrote {len(out)} rows for {out.instrument.nunique()} stocks; failed: {failed}")
