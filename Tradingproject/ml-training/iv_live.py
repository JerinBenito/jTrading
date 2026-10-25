"""Option-implied volatility for the risk model, kept current night by night.

  iv_history.csv.gz   committed history (date, sym, iv30; annualised %) built by fo_iv.py from NSE's free F&O bhavcopy, 2022-03 ->
  top_up()            fetches any newer bhavcopy days straight from NSE (public archive, plain HTTP) and computes their IV with the
                      same code (fo_iv.atm_points / iv30_table); never raises - if NSE cannot be reached the history simply ends
                      earlier and the risk model falls back to its non-IV version for that night
  attach()            adds the four model features (iv30, iv_d, iv_rel, iv_chg) to the risk model's frame

Walk-forward result (600 days, same rows): next-day range MAE 0.7041 -> 0.6854 (-2.7%), paired t = +7.9 vs the same model without IV.
Refresh the committed history (and rebuild it from scratch) with:  python fo_bhavcopy_fetch.py && python fo_iv.py && python iv_live.py --save
"""
import datetime as dt
import os, sys, time
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
HISTORY = os.path.join(HERE, "iv_history.csv.gz")
IV4 = ["iv30", "iv_d", "iv_rel", "iv_chg"]
ALIAS = {"TATAMOTORS": "TMPV"}
# NSE publishes the day's F&O bhavcopy in the evening (about 18:30-19:30 IST)
EARLIEST_IST_MINUTES = 19 * 60


def load_history():
    if not os.path.exists(HISTORY):
        return pd.DataFrame(columns=["date", "sym", "iv30"])
    return pd.read_csv(HISTORY)


def save_from_research_output():
    src = os.path.join(HERE, "data", "fo", "iv_daily.csv")
    d = pd.read_csv(src)[["date", "sym", "iv30"]].round({"iv30": 3})
    d.to_csv(HISTORY, index=False, compression="gzip")
    print(f"saved {len(d):,} rows {d.date.min()}..{d.date.max()} -> {HISTORY} ({os.path.getsize(HISTORY) / 1e3:.0f} KB)")


def _weekdays_after(last, upto):
    d = last + dt.timedelta(days=1)
    while d <= upto:
        if d.weekday() < 5:
            yield d
        d += dt.timedelta(days=1)


def _status(d):
    """HTTP status of the day's UDiFF bhavcopy file (HEAD): 200 published, 404 not (yet) published, anything else / None = NSE
    unreachable from here (blocked datacenter IP, outage) - in which case waiting for it would be pointless."""
    import urllib.error, urllib.request as ur
    import fo_bhavcopy_fetch as fb
    url = f"https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{d:%Y%m%d}_F_0000.csv.zip"
    try:
        return ur.urlopen(ur.Request(url, headers=fb.UA, method="HEAD"), timeout=30).status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:  # noqa: BLE001
        return None


def top_up(hist, frames, session, deadline=None, log=print):
    """Adds IV for every trading day after the end of `hist` up to `session` (inclusive) that NSE has published.
    frames: {symbol: daily frame with tradingDate, close} (the stock's cash close is the spot used for the inversion)."""
    import fo_bhavcopy_fetch as fb
    import fo_iv
    session = session if isinstance(session, dt.date) else dt.date.fromisoformat(str(session))
    last = dt.date.fromisoformat(str(hist["date"].max())) if len(hist) else dt.date(2022, 3, 1)
    basket = [s for s in frames if s not in ("NIFTY", "BANKNIFTY")]
    wanted = set(basket) | set(ALIAS.values())
    spot = {s: pd.Series({str(pd.Timestamp(t).date()): float(c) for t, c in zip(f["tradingDate"], f["close"])}) for s, f in frames.items() if s in basket}
    added = []
    for d in _weekdays_after(last, session):
        status = _status(d)
        if status is None or status not in (200, 404):
            log(f"    NSE archive not reachable from here (HTTP {status}) - going without implied vol tonight")
            break
        while status == 404 and d == session and deadline is not None and time.time() < deadline:
            log(f"    bhavcopy for {d} not published yet - waiting")
            time.sleep(120)
            status = _status(d)
        raw = fb.fetch(d) if status == 200 else None
        if raw is None:
            log(f"    no bhavcopy for {d} (holiday, not published yet, or NSE unreachable)")
            continue
        try:
            opts = fb.parse(raw, d, wanted)
            _, atm = fo_iv.atm_points(opts, lambda s, dd: spot[s].get(dd, np.nan), basket, ALIAS)
            day = fo_iv.iv30_table(atm)
        except Exception as e:  # noqa: BLE001
            log(f"    bhavcopy for {d} could not be parsed: {e!r}")
            continue
        if len(day):
            added.append(day[["date", "sym", "iv30"]])
            log(f"    IV for {d}: {len(day)} stocks (median {day.iv30.median():.1f}%)")
    if added:
        hist = pd.concat([hist] + added, ignore_index=True).drop_duplicates(["date", "sym"], keep="last")
    return hist


def attach(d, hist):
    """Adds IV4 columns to the risk model frame `d` (instrument, tradingDate). Rows without an IV reading get NaN."""
    h = hist.rename(columns={"sym": "instrument", "date": "tradingDate"}).copy()
    h["tradingDate"] = pd.to_datetime(h["tradingDate"])
    h = h.sort_values(["instrument", "tradingDate"])
    g = h.groupby("instrument")["iv30"]
    h["iv_d"] = h["iv30"] / np.sqrt(252)
    h["iv_rel"] = h["iv30"] / g.transform(lambda s: s.rolling(60, min_periods=20).mean())
    h["iv_chg"] = g.transform(lambda s: s.pct_change() * 100)
    d = d.drop(columns=[c for c in IV4 if c in d.columns])
    return d.merge(h[["instrument", "tradingDate"] + IV4], on=["instrument", "tradingDate"], how="left")


if __name__ == "__main__":
    if "--save" in sys.argv:
        save_from_research_output()
