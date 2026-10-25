"""MCX commodity report: for gold (Petal / Mini / Gold), silver (Micro / Mini / Silver), crude (Mini / Crude), natural gas (Mini / full),
copper and aluminium - the live futures contracts with their expiry dates, the term structure (carry between expiries), the
option-implied move to the next option expiry, and volatility-scaled "how high / how low can it get, and where does it close"
ranges for 5 / 10 / 21 trading days and to expiry.  Pushed to the backend (/api/commodities/report) for the app's Commodities tab.

What the numbers are (validated in commodity_research.py / commodity_quantiles.py, 26 years of futures history):
  * RANGES - the peak / dip a price reaches within H trading days, scaled by today's volatility. The 90% bands held 86-93% on
    10 years of unseen data (gold's peak side is the weakest at ~86%: gold trended up).
  * DIRECTION - NOT predicted. After a strong one-month rally the next 21 days were statistically no different from normal
    (pooled t = +0.3, sign flips between periods and commodities), so the report shows what happened after similar moves and says so.
Uses only the public backend (MCX candle pass-through) and the public Upstox instrument file; no secrets.
Usage: python commodity_report.py [--dry]
"""
import gzip, json, math, os, sys, datetime as dt
import urllib.error, urllib.parse as p, urllib.request as u
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
B = os.environ.get("TRADING_API", "https://jerintradingsignal.duckdns.org")
MASTER = "https://assets.upstox.com/market-quote/instruments/exchange/MCX.json.gz"
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
NOW = dt.datetime.now(IST)
TODAY = NOW.date()
MIN_OF_DAY = NOW.hour * 60 + NOW.minute
SESSION_DONE = NOW.weekday() >= 5 or MIN_OF_DAY >= 23 * 60 + 35 or MIN_OF_DAY < 9 * 60   # otherwise today's daily candle is still forming
HORIZONS = [5, 10, 21]
# underlying -> (family, display name, price unit, units of that price per lot)
PRODUCTS = {
    "GOLDPETAL": ("GOLD", "Gold Petal", "per gram", 1),
    "GOLDM": ("GOLD", "Gold Mini", "per 10 g", 10),
    "GOLD": ("GOLD", "Gold", "per 10 g", 100),
    "SILVERMIC": ("SILVER", "Silver Micro", "per kg", 1),
    "SILVERM": ("SILVER", "Silver Mini", "per kg", 5),
    "SILVER": ("SILVER", "Silver", "per kg", 30),
    "CRUDEOILM": ("CRUDE", "Crude Oil Mini", "per barrel", 10),
    "CRUDEOIL": ("CRUDE", "Crude Oil", "per barrel", 100),
    "NATGASMINI": ("NATGAS", "Natural Gas Mini", "per mmBtu", 250),
    "NATURALGAS": ("NATGAS", "Natural Gas", "per mmBtu", 1250),
    "COPPER": ("COPPER", "Copper", "per kg", 2500),
    "ALUMINIUM": ("ALUMINIUM", "Aluminium", "per kg", 5000),
}
OPTION_UNDERLYINGS = {"GOLDM", "GOLD", "SILVERM", "SILVER", "CRUDEOILM", "CRUDEOIL", "NATGASMINI", "NATURALGAS", "COPPER"}
FAMILY_NAMES = {"GOLD": "Gold", "SILVER": "Silver", "CRUDE": "Crude oil", "NATGAS": "Natural gas", "COPPER": "Copper", "ALUMINIUM": "Aluminium"}
Q = json.load(open(os.path.join(HERE, "commodity_quantiles.json")))
PROBS = Q["probs"]


def http(url, timeout=120, retries=3):
    last = None
    for i in range(retries):
        try:
            return u.urlopen(u.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=timeout).read()
        except Exception as e:          # noqa: BLE001
            last = e
    raise last


_cache = {}


def candles(key, days_back=430, unit="days"):
    ck = (key, days_back, unit)
    if ck not in _cache:
        a = (TODAY - dt.timedelta(days=days_back)).isoformat()
        raw = json.loads(http(f"{B}/api/commodities/candles?instrumentKey={p.quote(key, safe='')}&unit={unit}&interval=1&from={a}&to={TODAY.isoformat()}"))
        d = pd.DataFrame(raw, columns=["ts", "open", "high", "low", "close", "volume"])
        if len(d):
            d["day"] = pd.to_datetime(d.ts, unit="s", utc=True).dt.tz_convert("Asia/Kolkata").dt.date
            d = d.sort_values("ts").drop_duplicates("day", keep="last").reset_index(drop=True)
            if not SESSION_DONE:
                d["partial"] = d.day == TODAY
            else:
                d["partial"] = False
        _cache[ck] = d
    return _cache[ck]


def interp(table, H):
    """Quantile vector for horizon H from the stored horizons (linear in log H, clamped to the table's range)."""
    hs = Q["horizons"]
    H = max(hs[0], min(hs[-1], H))
    if H in hs:
        return np.array(table[str(H)])
    j = max(i for i, h in enumerate(hs) if h < H)
    w = (math.log(H) - math.log(hs[j])) / (math.log(hs[j + 1]) - math.log(hs[j]))
    return (1 - w) * np.array(table[str(hs[j])]) + w * np.array(table[str(hs[j + 1])])


def qv(vec, prob):
    return float(np.interp(prob, PROBS, vec))


def edate(ms):
    return dt.datetime.fromtimestamp(ms / 1000, IST).date()


def trading_days_to(d):
    return int(np.busday_count(TODAY + dt.timedelta(days=1), d + dt.timedelta(days=1)))


def stitched_returns(contract_frames):
    """Daily log returns of the most-traded contract each day (each return within a single contract, so rolls never leak in)."""
    rows = {}
    for key, d in contract_frames.items():
        d = d[~d.partial] if "partial" in d else d
        if len(d) < 2:
            continue
        r = np.log(d.close / d.close.shift(1))
        for day, ret, vol in zip(d.day[1:], r[1:], d.volume[1:]):
            if np.isfinite(ret) and (day not in rows or vol > rows[day][1]):
                rows[day] = (ret, vol)
    s = pd.Series({k: v[0] for k, v in rows.items()}).sort_index()
    return s


def build_product(und, master, now_ms):
    fam, name, unit, units = PRODUCTS[und]
    futs = sorted((x for x in master if x["instrument_type"] == "FUT" and x["underlying_symbol"] == und and x["expiry"] >= now_ms), key=lambda x: x["expiry"])[:4]
    if not futs:
        return None
    frames, contracts = {}, []
    for x in futs:
        d = candles(x["instrument_key"])
        if d.empty:
            continue
        frames[x["instrument_key"]] = d
        last, prev = d.iloc[-1], d.iloc[-2] if len(d) > 1 else None
        contracts.append({"symbol": x["trading_symbol"], "key": x["instrument_key"], "expiry": edate(x["expiry"]).isoformat(),
                          "daysToExpiry": (edate(x["expiry"]) - TODAY).days, "tradingDaysToExpiry": trading_days_to(edate(x["expiry"])),
                          "price": float(last.close), "asOf": str(last.day), "volume": int(last.volume),
                          "avgVolume5": float(d.volume.tail(5).mean()),
                          "dayChangePct": round(float((last.close / prev.close - 1) * 100), 2) if prev is not None else None})
    if not contracts:
        return None
    ref = max(contracts[:3], key=lambda c: c["avgVolume5"])               # the most-traded of the nearest three expiries
    for c in contracts:
        c["isReference"] = c is ref
        c["spreadVsRefPct"] = round((c["price"] / ref["price"] - 1) * 100, 2)
    for a, b in zip(contracts, contracts[1:]):
        gap = (dt.date.fromisoformat(b["expiry"]) - dt.date.fromisoformat(a["expiry"])).days
        b["carryAnnualPct"] = round(((b["price"] / a["price"]) ** (365 / gap) - 1) * 100, 1) if gap > 0 else None
    shape = "flat"
    if len(contracts) > 1:
        sp = contracts[1]["price"] / contracts[0]["price"] - 1
        shape = "contango (later expiries cost more)" if sp > 0.002 else "backwardation (later expiries cost less)" if sp < -0.002 else "flat"

    rets = stitched_returns(frames)
    if len(rets) < 40:
        return None
    sig = float(np.sqrt((rets ** 2).ewm(alpha=0.06, adjust=False).mean().iloc[-1]))
    trail = float(rets.tail(Q["trailDays"]).sum())
    z = trail / (sig * math.sqrt(Q["trailDays"]))
    bucket = "strong down" if z < -1.5 else "down" if z < -0.5 else "flat" if z < 0.5 else "up" if z < 1.5 else "strong up"
    price = ref["price"]
    fq = Q["families"][fam]

    def horizon(label, H, exp=None):
        s = sig * math.sqrt(H)
        pk, dp, fin = interp(fq["peak"], H), interp(fq["dip"], H), interp(fq["close"], H)
        gb = interp(fq["giveback"], H)

        def lvl(x, up=True):
            return round(price * math.exp(x if up else -x), 2)

        out = {"label": label, "days": H}
        if exp:
            out["expiry"] = exp
        out["peak"] = {k: {"pct": round((math.exp(s * qv(pk, pr)) - 1) * 100, 2), "price": lvl(s * qv(pk, pr))} for k, pr in (("p50", .5), ("p75", .75), ("p90", .9))}
        out["dip"] = {k: {"pct": round((math.exp(-s * qv(dp, pr)) - 1) * 100, 2), "price": lvl(s * qv(dp, pr), False)} for k, pr in (("p50", .5), ("p75", .75), ("p90", .9))}
        out["close"] = {k: {"pct": round((math.exp(s * qv(fin, pr)) - 1) * 100, 2), "price": round(price * math.exp(s * qv(fin, pr)), 2)}
                        for k, pr in (("p10", .1), ("p50", .5), ("p90", .9))}
        out["givebackMedianPct"] = round((math.exp(s * qv(gb, .5)) - 1) * 100, 2)
        return out

    forecasts = [horizon(f"Next {H} sessions", H) for H in HORIZONS]
    h_exp = min(max(ref["tradingDaysToExpiry"], 1), 63)
    same = next((f for f in forecasts if f["days"] == h_exp), None)
    if same:                                   # expiry is exactly one of the fixed horizons: label it instead of repeating the row
        same["expiry"] = ref["expiry"]
    else:
        forecasts.append(horizon("Until " + ref["symbol"].split(" FUT ")[-1].title() + " expiry", h_exp, ref["expiry"]))

    after = None
    pooled = Q["pooledByTrailingMove"]["21"][bucket]
    s21 = sig * math.sqrt(21)
    after = {"bucket": bucket, "trailingMovePct": round((math.exp(trail) - 1) * 100, 2), "z": round(z, 2), "samples": pooled["n"],
             "pUp": pooled["pUp"], "pUpOthers": pooled["pUpOthers"], "meanCloseZ": pooled["meanClose"], "meanCloseZOthers": pooled["meanCloseOthers"], "t": pooled["tVsOthers"],
             "peakMedianPct": round((math.exp(s21 * qv(pooled["peak"], .5)) - 1) * 100, 2), "peakP90Pct": round((math.exp(s21 * qv(pooled["peak"], .9)) - 1) * 100, 2),
             "dipMedianPct": round((math.exp(-s21 * qv(pooled["dip"], .5)) - 1) * 100, 2), "dipP90Pct": round((math.exp(-s21 * qv(pooled["dip"], .9)) - 1) * 100, 2),
             "distinguishableFromNormal": abs(pooled["tVsOthers"]) >= 2.0}

    opts = options_block(und, master, now_ms, sig, frames)
    last = contracts[0]
    return {"underlying": und, "family": fam, "familyName": FAMILY_NAMES[fam], "name": name, "priceUnit": unit, "lotUnits": units,
            "price": price, "referenceContract": ref["symbol"], "dayChangePct": ref["dayChangePct"], "asOf": ref["asOf"],
            "lotNotional": round(price * units), "contracts": contracts, "termStructure": shape,
            "sigmaDailyPct": round(sig * 100, 3), "sigmaAnnualPct": round(sig * math.sqrt(252) * 100, 1),
            "trail21Pct": round((math.exp(trail) - 1) * 100, 2), "trailZ": round(z, 2), "trailBucket": bucket,
            "forecasts": forecasts, "afterSimilarMoves": after, "options": opts, "hasOptions": und in OPTION_UNDERLYINGS,
            "stale": (TODAY - dt.date.fromisoformat(ref["asOf"])).days > 4 or last["volume"] == 0 and ref["volume"] == 0}


def options_block(und, master, now_ms, sig, frames):
    if und not in OPTION_UNDERLYINGS:
        return None
    opts = [x for x in master if x["instrument_type"] in ("CE", "PE") and x["underlying_symbol"] == und and x["expiry"] >= now_ms]
    if not opts:
        return None
    expiries = sorted({x["expiry"] for x in opts})
    block = {"expiries": [{"date": edate(e).isoformat(), "daysToExpiry": (edate(e) - TODAY).days} for e in expiries[:6]], "implied": None}
    for e in expiries:
        if (edate(e) - TODAY).days < 2:
            continue
        chain = [x for x in opts if x["expiry"] == e]
        fkey = chain[0]["underlying_key"]
        fut = frames.get(fkey)
        if fut is None:
            fut = candles(fkey)
        if fut is None or fut.empty:
            break
        F = float(fut.iloc[-1].close)
        strike = min({x["strike_price"] for x in chain}, key=lambda s: abs(s - F))
        px = {}
        for t in ("CE", "PE"):
            c = [x for x in chain if x["strike_price"] == strike and x["instrument_type"] == t]
            if not c:
                break
            d = candles(c[0]["instrument_key"], days_back=12)
            if d.empty or (TODAY - d.iloc[-1].day).days > 4 or d.iloc[-1].volume <= 0:
                break
            px[t] = float(d.iloc[-1].close)
        if len(px) < 2:
            break
        T = max((edate(e) - TODAY).days, 1) / 365
        straddle = px["CE"] + px["PE"]
        iv = straddle / (0.7979 * F * math.sqrt(T))
        block["implied"] = {"expiry": edate(e).isoformat(), "daysToExpiry": (edate(e) - TODAY).days, "underlying": next((x["trading_symbol"] for x in master if x["instrument_key"] == fkey), None),
                            "futuresPrice": F, "strike": strike, "call": px["CE"], "put": px["PE"], "straddle": round(straddle, 2),
                            "expectedMovePct": round(straddle / F * 100, 2), "impliedVolAnnualPct": round(iv * 100, 1),
                            "oneSigmaMovePct": round(iv * math.sqrt(T) * 100, 2),
                            "vsRealizedVol": round(iv / (sig * math.sqrt(252)), 2),
                            "note": "Straddle = call + put at the strike nearest the futures price: the market's price for the size of the move (either way) by that option expiry."}
        break
    return block


def main():
    dry = "--dry" in sys.argv
    master = json.loads(gzip.decompress(http(MASTER, timeout=180)))
    now_ms = NOW.timestamp() * 1000
    master = [x for x in master if x.get("segment") == "MCX_FO" and x["underlying_symbol"] in PRODUCTS and x["instrument_type"] in ("FUT", "CE", "PE")]
    products = []
    for und in PRODUCTS:
        try:
            r = build_product(und, master, now_ms)
        except Exception as e:      # noqa: BLE001 - one product failing must not lose the others
            print(f"  {und}: FAILED {e!r}", flush=True)
            continue
        if r:
            products.append(r)
            f21 = next(f for f in r["forecasts"] if f["days"] == 21 and "expiry" not in f)
            print(f"  {und:11s} {r['price']:>10,.1f} {r['dayChangePct']:+6.2f}%  sigma {r['sigmaAnnualPct']:5.1f}%  21d peak p50/p90 {f21['peak']['p50']['pct']:+.1f}/{f21['peak']['p90']['pct']:+.1f}%  "
                  f"dip {f21['dip']['p50']['pct']:+.1f}/{f21['dip']['p90']['pct']:+.1f}%  options {'IV %.0f%%' % r['options']['implied']['impliedVolAnnualPct'] if r['options'] and r['options']['implied'] else '-'}", flush=True)
    if len(products) < 6:
        raise SystemExit(f"only {len(products)} products built - not pushing a partial report")
    covs = [v for byfam in Q["validation"].values() for c in byfam.values() for v in c.values()]
    cov_lo, cov_hi = min(covs), max(covs)
    report = {"reportDate": TODAY.isoformat(), "generatedAt": NOW.isoformat(timespec="seconds"), "sessionComplete": SESSION_DONE,
              "products": products,
              "validation": {"quantilesBuilt": Q["built"], "history": {k: v["from"][:4] + "-" + v["to"][:4] for k, v in Q["families"].items()},
                             "q90Coverage": Q["validation"]},
              "notes": [
                  f"Ranges, not predictions: the peak / dip bands scale with today's volatility; the 90% bands held {cov_lo:.0f}-{cov_hi:.0f}% of the time on 10 years of unseen data.",
                  "Direction is NOT forecast. After strong one-month moves, the following 21 days were statistically no different from normal; the sign flipped between decades and commodities.",
                  "The bands contain each market's own historical drift (for example gold's long rally), which is why gold's upside band is wider than its downside band. That is history, not a call.",
                  "Gold's upper (peak) band is the weakest: it held about 86% because gold trended up through the test period.",
                  "Prices are MCX in rupees, so the rupee rate and import duty move them too; the ranges use the volatility of the MCX contract itself.",
                  "Costs (brokerage, charges, taxes) and margin are not included. Roughly 9 in 10 individual F&O traders lost money in FY25 (SEBI)."]}
    body = json.dumps(report, separators=(",", ":"), default=str)
    print(f"report: {len(products)} products, {len(body):,} bytes")
    if dry:
        open(os.path.join(HERE, "data", "commodity_report_preview.json"), "w").write(body)
        return
    req = u.Request(B + "/api/commodities/report", data=body.encode(), headers={"Content-Type": "application/json"}, method="POST")
    print("pushed:", u.urlopen(req, timeout=120).read().decode())


if __name__ == "__main__":
    main()
