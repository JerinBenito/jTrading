"""Downloads NSE's free daily F&O bhavcopy (end-of-day prices for every option/future) for every weekday Jan 2022 -> today
and keeps only what is needed to compute implied volatility for the NIFTY 50 basket + NIFTY/BANKNIFTY:
option rows (OPTSTK/OPTIDX) for those symbols with expiry <= 75 days out, plus the near-month future (a spot/carry proxy).
Two formats: old  .../content/historical/DERIVATIVES/<YYYY>/<MON>/fo<DD><MON><YYYY>bhav.csv.zip  (until early Jul 2024) and
UDiFF .../content/fo/BhavCopy_NSE_FO_0_0_0_<YYYYMMDD>_F_0000.csv.zip (from Jul 2024). Plain HTTP, no cookies.
Output: data/fo/<YYYY-MM>.csv.gz with a unified schema. Resumable per month. Usage: python fo_bhavcopy_fetch.py [workers=4]
"""
import calendar, io, json, os, sys, time, urllib.error, urllib.request as u, zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

import pandas as pd

B = "https://jerintradingsignal.duckdns.org"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "fo")
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"}
WORKERS = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 4      # (also imported by iv_live.py, whose argv is not ours)
ALIASES = {"TMPV": "TATAMOTORS"}                     # the file uses the pre-demerger ticker for older dates
MON = [m.upper() for m in calendar.month_abbr[1:]]


def fetch(d):
    urls = []
    if d >= date(2024, 7, 1):
        urls.append(f"https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{d:%Y%m%d}_F_0000.csv.zip")
    if d <= date(2024, 7, 10):
        urls.append(f"https://nsearchives.nseindia.com/content/historical/DERIVATIVES/{d.year}/{MON[d.month - 1]}/fo{d.day:02d}{MON[d.month - 1]}{d.year}bhav.csv.zip")
    for url in urls:
        for attempt in range(3):
            try:
                return u.urlopen(u.Request(url, headers=UA), timeout=60).read()
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    break
            except Exception:
                time.sleep(2 * (attempt + 1))
    return None


def parse(raw, d, wanted):
    z = zipfile.ZipFile(io.BytesIO(raw))
    df = pd.read_csv(z.open(z.namelist()[0]), low_memory=False)
    if "TckrSymb" in df.columns:                                       # UDiFF
        df = df.rename(columns={"TckrSymb": "sym", "FinInstrmTp": "tp", "XpryDt": "expiry", "StrkPric": "strike", "OptnTp": "opt",
                                "SttlmPric": "settle", "ClsPric": "close", "UndrlygPric": "und", "OpnIntrst": "oi", "TtlTradgVol": "vol"})
        df["tp"] = df["tp"].map({"STO": "OPT", "IDO": "OPT", "STF": "FUT", "IDF": "FUT"})
    else:                                                              # old format
        df = df.rename(columns={"SYMBOL": "sym", "INSTRUMENT": "tp", "EXPIRY_DT": "expiry", "STRIKE_PR": "strike", "OPTION_TYP": "opt",
                                "SETTLE_PR": "settle", "CLOSE": "close", "OPEN_INT": "oi", "CONTRACTS": "vol"})
        df["tp"] = df["tp"].map({"OPTSTK": "OPT", "OPTIDX": "OPT", "FUTSTK": "FUT", "FUTIDX": "FUT"})
        df["und"] = float("nan")
        df["expiry"] = pd.to_datetime(df["expiry"], format="%d-%b-%Y").dt.strftime("%Y-%m-%d")
    df = df[df["sym"].isin(wanted) & df["tp"].notna()].copy()
    df["date"] = d.isoformat()
    exp = pd.to_datetime(df["expiry"])
    df = df[(exp - pd.Timestamp(d)).dt.days.between(0, 75)]
    return df[["date", "sym", "tp", "expiry", "strike", "opt", "settle", "close", "und", "oi", "vol"]]


def job(args):
    d, wanted = args
    raw = fetch(d)
    if raw is None:
        return d, None
    return d, parse(raw, d, wanted)


if __name__ == "__main__":
    basket = [r["symbol"] for r in json.loads(u.urlopen(B + "/api/monitor/basket", timeout=60).read())]
    wanted = set(basket) | set(ALIASES.values())
    days, d = [], date(2022, 1, 3)
    while d <= date.today():
        if d.weekday() < 5:
            days.append(d)
        d += timedelta(days=1)
    os.makedirs(OUT, exist_ok=True)
    months = sorted({(x.year, x.month) for x in days})
    t0, files, missing = time.time(), 0, 0
    for (y, m) in months:
        path = os.path.join(OUT, f"{y}-{m:02d}.csv.gz")
        if os.path.exists(path) and (y, m) != (date.today().year, date.today().month):
            continue
        md = [x for x in days if x.year == y and x.month == m]
        with ThreadPoolExecutor(WORKERS) as ex:
            res = list(ex.map(job, [(x, wanted) for x in md]))
        frames = [r for _, r in res if r is not None]
        missing += sum(1 for _, r in res if r is None)
        if frames:
            pd.concat(frames, ignore_index=True).to_csv(path + ".part", index=False, compression="gzip")
            os.replace(path + ".part", path)
            files += len(frames)
        print(f"{y}-{m:02d}: {len(frames)}/{len(md)} trading days ({time.time() - t0:.0f}s elapsed)", flush=True)
    print(f"DONE: {files} daily files kept, {missing} weekdays without a file (holidays) in {time.time() - t0:.0f}s", flush=True)
