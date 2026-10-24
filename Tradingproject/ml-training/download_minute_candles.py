"""Downloads 1-minute candles (Jan 2022 -> today) for the NIFTY 50 basket + NIFTY/BANKNIFTY to the LOCAL disk for offline
research - never to the server. Uses the key-protected pass-through /api/research/candles/{symbol} (the Upstox token only
lives on the server). Resumable: each stock-month is one file data/minute/<SYMBOL>/<YYYY-MM>.csv.gz and existing files are
skipped, so it can be stopped and restarted any time. Rows: ts(UTC epoch s), open, high, low, close, volume (index volume = 0).

Usage: python download_minute_candles.py <key-file> [workers=3]
"""
import calendar, gzip, json, os, sys, time, urllib.error, urllib.request as u
from concurrent.futures import ThreadPoolExecutor
from datetime import date

import pandas as pd

B = "https://jerintradingsignal.duckdns.org"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "minute")
KEY = open(sys.argv[1]).read().strip()
WORKERS = int(sys.argv[2]) if len(sys.argv) > 2 else 3


def months(start=(2022, 1)):
    y, m = start
    today = date.today()
    while (y, m) <= (today.year, today.month):
        yield y, m
        m += 1
        if m == 13:
            y, m = y + 1, 1


def fetch(sym, a, b, attempts=4):
    last = None
    for i in range(attempts):
        try:
            req = u.Request(f"{B}/api/research/candles/{sym}?unit=minutes&interval=1&from={a}&to={b}", headers={"X-Research-Key": KEY})
            return json.loads(u.urlopen(req, timeout=180).read())
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (400, 404):            # e.g. before the stock was listed - not retryable
                return []
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(3 * (i + 1))
    raise RuntimeError(f"{sym} {a}..{b}: {last!r}")


def job(args):
    sym, y, m = args
    path = os.path.join(OUT, sym, f"{y}-{m:02d}.csv.gz")
    if os.path.exists(path):
        return sym, y, m, -1
    a, b = date(y, m, 1), date(y, m, calendar.monthrange(y, m)[1])
    rows = fetch(sym, a.isoformat(), min(b, date.today()).isoformat())
    df = pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close", "volume"]).sort_values("ts")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    df.to_csv(tmp, index=False, compression="gzip")
    os.replace(tmp, path)
    return sym, y, m, len(df)


if __name__ == "__main__":
    symbols = [r["symbol"] for r in json.loads(u.urlopen(B + "/api/monitor/basket", timeout=60).read())]
    todo = [(s, y, m) for s in symbols for y, m in months()]
    print(f"{len(symbols)} instruments x {len(todo) // len(symbols)} months = {len(todo)} requests, {WORKERS} workers -> {OUT}", flush=True)
    t0, done, rows, skipped, failed = time.time(), 0, 0, 0, []
    with ThreadPoolExecutor(WORKERS) as ex:
        for res in ex.map(lambda a: (lambda: job(a))() if True else None, todo):
            done += 1
            if res[3] == -1:
                skipped += 1
            else:
                rows += res[3]
            if done % 100 == 0:
                print(f"  {done}/{len(todo)} done ({skipped} already on disk), {rows:,} rows written, {time.time() - t0:.0f}s", flush=True)
    print(f"DONE in {time.time() - t0:.0f}s: {rows:,} rows written, {skipped} files already present", flush=True)
