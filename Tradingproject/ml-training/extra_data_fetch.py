"""Free outside data for the data audit (2026-10-28), saved under data/extra/:
  nse_board_meetings.csv   official board-meeting dates per basket stock (purpose 'Financial Results' = results day)
  nse_corp_actions.csv     splits / bonus / demergers / dividends per stock (data-hygiene check)
  participant_oi.csv       NSE participant-wise F&O open interest per day (FII / DII / Pro / Client), 2022 ->
  overseas.csv             Yahoo daily closes: Nikkei, Hang Seng, KOSPI, S&P 500, US VIX
The NSE website API answers plain scripts when sent browser-like headers (no cookie needed); the archives host needs none.
Usage: python extra_data_fetch.py
"""
import csv, io, json, os, time, urllib.error, urllib.parse as p, urllib.request as u
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "data", "extra")
os.makedirs(OUT, exist_ok=True)
B = "https://jerintradingsignal.duckdns.org"
H = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36", "Accept": "application/json, text/plain, */*",
     "Accept-Language": "en-US,en;q=0.9", "Referer": "https://www.nseindia.com/"}


def get(url, tries=3, timeout=60):
    for i in range(tries):
        try:
            return u.urlopen(u.Request(url, headers=H), timeout=timeout).read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(2 * (i + 1))
        except Exception:
            time.sleep(2 * (i + 1))
    return None


basket = [r["symbol"] for r in json.loads(u.urlopen(B + "/api/monitor/basket", timeout=60).read()) if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
ALIAS = {"TMPV": ["TMPV", "TATAMOTORS"]}


def per_symbol(kind):
    def job(sym):
        rows = []
        for nse_sym in ALIAS.get(sym, [sym]):
            if kind == "bm":
                url = f"https://www.nseindia.com/api/corporate-board-meetings?index=equities&symbol={p.quote(nse_sym)}&from_date=01-01-2021&to_date=31-12-2026"
            else:
                url = f"https://www.nseindia.com/api/corporates-corporateActions?index=equities&symbol={p.quote(nse_sym)}&from_date=01-01-2021&to_date=31-12-2026"
            raw = get(url)
            if raw:
                try:
                    for r in json.loads(raw):
                        r["symbol"] = sym
                        rows.append(r)
                except Exception:
                    pass
            time.sleep(0.4)
        return sym, rows
    return job


for kind, name in (("bm", "nse_board_meetings.csv"), ("ca", "nse_corp_actions.csv")):
    with ThreadPoolExecutor(3) as ex:
        res = list(ex.map(per_symbol(kind), basket))
    rows = [r for _, rs in res for r in rs]
    pd.DataFrame(rows).to_csv(os.path.join(OUT, name), index=False)
    print(f"{name}: {len(rows)} rows for {sum(1 for _, rs in res if rs)}/{len(basket)} stocks", flush=True)

# participant-wise open interest, one small CSV per trading day
def poi(d):
    raw = get(f"https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_{d:%d%m%Y}.csv", tries=2, timeout=30)
    if not raw:
        return None
    df = pd.read_csv(io.BytesIO(raw), skiprows=1)
    df.columns = [c.strip() for c in df.columns]
    df = df[df["Client Type"].isin(["Client", "DII", "FII", "Pro"])].copy()
    df.insert(0, "date", d.isoformat())
    return df


days, d = [], date(2022, 1, 3)
while d <= date.today():
    if d.weekday() < 5:
        days.append(d)
    d += timedelta(days=1)
with ThreadPoolExecutor(4) as ex:
    parts = [x for x in ex.map(poi, days) if x is not None]
pd.concat(parts, ignore_index=True).to_csv(os.path.join(OUT, "participant_oi.csv"), index=False)
print(f"participant_oi.csv: {len(parts)} trading days of {len(days)} weekdays", flush=True)

# overseas indices from Yahoo
frames = []
for sym, name in (("^N225", "nikkei"), ("^HSI", "hangseng"), ("^KS11", "kospi"), ("^GSPC", "sp500"), ("^VIX", "usvix")):
    p1 = int(datetime(2021, 6, 1, tzinfo=timezone.utc).timestamp()); p2 = int(time.time())
    raw = get(f"https://query1.finance.yahoo.com/v8/finance/chart/{p.quote(sym)}?period1={p1}&period2={p2}&interval=1d")
    if not raw:
        print("yahoo failed for", sym); continue
    j = json.loads(raw)["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    df = pd.DataFrame({"date": pd.to_datetime(j["timestamp"], unit="s").normalize(), name: q["close"], name + "_hi": q["high"], name + "_lo": q["low"], name + "_op": q["open"]}).dropna(subset=[name])
    frames.append(df.set_index("date"))
    print(f"yahoo {sym}: {len(df)} days", flush=True)
pd.concat(frames, axis=1).reset_index().to_csv(os.path.join(OUT, "overseas.csv"), index=False)
print("done")
