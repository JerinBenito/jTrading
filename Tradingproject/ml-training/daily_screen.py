"""End-of-day market screen for the NIFTY 50 basket, pushed to the backend for the app's Screen tab.

Run after the close (the final 15:15 hourly candle must be in): market context, a per-stock evidence dossier
(levels, typical ranges, what the stock did after comparable days), ranked lists, and a handful of
PRE-REGISTERED hypotheses for the next session. Earlier days' hypotheses are scored automatically against what
actually happened and kept in a running tally, so the app reports its own hit rate honestly.

Descriptive statistics only - not recommendations. None of the screened attributes has been shown to predict
returns on this basket (see the project's backtests); the hypotheses exist to be tested, not followed.

Data rules (learned the hard way): today's STORED daily row is a mid-morning snapshot, so today's close comes
from the final hourly candle (/api/monitor/basket lastClose), today's volume from volumeSoFarRatio, and every
history statistic uses COMPLETED days only.

Usage: python daily_screen.py [--no-push] [--wait-minutes N]
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse as p
import urllib.request as u
from datetime import date, datetime, timedelta, timezone

import numpy as np
import pandas as pd

B = "https://jerintradingsignal.duckdns.org"
FINAL_CANDLE_UTC = "09:45"            # the 15:15-15:30 IST candle
RESULTS_REACTION_MOVE = 2.0          # H1 threshold, % (reaction-day average on 342 past events: 2.38% vs 1.15% normal)
IST = timezone(timedelta(hours=5, minutes=30))


def get(path, timeout=120, attempts=3):
    for i in range(attempts):
        try:
            return json.loads(u.urlopen(B + path, timeout=timeout).read())
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if i == attempts - 1:
                raise
            time.sleep(4 * (i + 1))


def post(path, payload):
    req = u.Request(B + path, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}, method="POST")
    return json.loads(u.urlopen(req, timeout=120).read())


def load_daily(sym):
    d = pd.DataFrame(get(f"/api/ml/features/{p.quote(sym)}"))
    for c in d.columns:
        if c not in ("instrument", "tradingDate"):
            d[c] = pd.to_numeric(d[c], errors="coerce")
    return d.sort_values("tradingDate").reset_index(drop=True)


def rsi14(c):
    dl = c.diff()
    up = dl.clip(lower=0).ewm(alpha=1 / 14, adjust=False).mean()
    dn = (-dl.clip(upper=0)).ewm(alpha=1 / 14, adjust=False).mean()
    return float((100 - 100 / (1 + up / dn)).iloc[-1])


def pctile(a, q):
    a = np.asarray(a, float)
    a = a[~np.isnan(a)]
    return float(np.percentile(a, q)) if len(a) else None


def rnd(x, n=2):
    return None if x is None or (isinstance(x, float) and np.isnan(x)) else round(float(x), n)


def stock_record(sym, session, feed_close, nifty_chg, results_date):
    d_all = load_daily(sym)
    d = d_all[d_all["tradingDate"] < session].reset_index(drop=True)    # completed days only
    y, close = d.iloc[-1], float(feed_close)
    c = pd.concat([d["close"], pd.Series([close])], ignore_index=True)
    ema = lambda n: float(c.ewm(span=n, adjust=False).mean().iloc[-1])
    live = get(f"/api/ml/intraday-features/{p.quote(sym)}/live") or {}
    open_today = float(d_all.iloc[-1]["open"])                          # today's open is final from 09:15
    chg = (close / y["close"] - 1) * 100
    nxt = (d["close"].shift(-1) / d["close"] - 1) * 100
    nxt5 = (d["close"].shift(-5) / d["close"] - 1) * 100
    rng = (d["high"] - d["low"]) / d["close"] * 100
    ret = d["dailyReturnPct"]
    lo_b, hi_b = (0.8 * chg, 1.4 * chg) if chg > 0 else (1.4 * chg, 0.8 * chg)
    like = ret[(ret >= lo_b) & (ret <= hi_b)].index
    like = like[like < len(d) - 5]
    rsi_now = rsi14(c)
    rs_series = pd.Series([rsi14(d["close"].iloc[: i + 1]) if i >= 20 else np.nan for i in range(len(d))])
    oversold_idx = rs_series[rs_series < 32].index
    oversold_idx = oversold_idx[oversold_idx < len(d) - 5]
    p5, p50, p95 = (pctile(nxt, q) for q in (5, 50, 95))
    # frame for the risk model: completed days + today's final numbers rebuilt from the last hourly candle (live row:
    # volatilitySoFarPct at the final hour IS the full-day (high-low)/open %, volumeSoFarRatio the full-day volume ratio)
    frame = d[["tradingDate", "open", "close", "volumeRatio20d", "gapFromPrevClosePct"]].rename(columns={"gapFromPrevClosePct": "gap"}).copy()
    frame["range"] = (d["high"] - d["low"]) / d["open"] * 100
    frame["tradingDate"] = pd.to_datetime(frame["tradingDate"])
    vol_today, rng_today = live.get("volumeSoFarRatio"), live.get("volatilitySoFarPct")
    frame = pd.concat([frame, pd.DataFrame([{
        "tradingDate": pd.Timestamp(session), "open": open_today, "close": close,
        "volumeRatio20d": np.nan if vol_today is None else float(vol_today), "gap": (open_today / y["close"] - 1) * 100,
        "range": np.nan if rng_today is None else float(rng_today)}])], ignore_index=True)
    levels = {"prevDayHigh": y["high"], "prevDayLow": y["low"], "high20d": d["high"].iloc[-20:].max(), "low20d": d["low"].iloc[-20:].min(),
              "high52w": d["high"].iloc[-252:].max(), "low52w": d["low"].iloc[-252:].min(),
              "ema21": ema(21), "ema50": ema(50), "ema200": ema(200)}
    trend = "up" if ema(9) > ema(21) > ema(50) else "down" if ema(9) < ema(21) < ema(50) else "mixed"
    return {
        "_frame": frame,
        "symbol": sym, "close": rnd(close), "prevClose": rnd(y["close"]), "chg": rnd(chg), "gap": rnd((open_today / y["close"] - 1) * 100),
        "openToClose": rnd((close / open_today - 1) * 100), "rsNifty": rnd(chg - nifty_chg),
        "volRatio": rnd(live.get("volumeSoFarRatio")), "rsi": rnd(rsi_now, 1), "atrPct": rnd(y["atr14"] / y["close"] * 100),
        "r5": rnd((close / d["close"].iloc[-5] - 1) * 100), "r20": rnd((close / d["close"].iloc[-20] - 1) * 100), "trend": trend,
        "resultsDate": results_date, "rangePct": rnd(rng_today),
        "levels": {k: {"price": rnd(v), "distPct": rnd((v / close - 1) * 100)} for k, v in levels.items()},
        "band": {"p5Pct": rnd(p5), "p50Pct": rnd(p50), "p95Pct": rnd(p95),
                 "low": rnd(close * (1 + p5 / 100)) if p5 is not None else None, "high": rnd(close * (1 + p95 / 100)) if p95 is not None else None,
                 "medianAbsMovePct": rnd(ret.abs().median()), "p90AbsMovePct": rnd(pctile(ret.abs(), 90)), "medianRangePct": rnd(rng.median()),
                 "upDaysPct": rnd(100 * (nxt.dropna() > 0).mean(), 0), "days": int(nxt.notna().sum())},
        "history": {
            "similarDays": {"n": int(len(like)), "nextDayMeanPct": rnd(nxt.loc[like].mean()), "nextDayUpPct": rnd(100 * (nxt.loc[like].dropna() > 0).mean(), 0) if len(like) else None,
                            "next5dMeanPct": rnd(nxt5.loc[like].mean())},
            "oversold": ({"n": int(len(oversold_idx)), "next5dMeanPct": rnd(nxt5.loc[oversold_idx].mean()),
                          "next5dUpPct": rnd(100 * (nxt5.loc[oversold_idx].dropna() > 0).mean(), 0)} if rsi_now < 32 and len(oversold_idx) >= 3 else None),
        },
    }


RISK_TIERS = ((30, "High"), (18, "Elevated"), (10, "Normal"), (0, "Low"))
EVENT_EFFECTS = {   # measured on 240 walk-forward days (vol_forecast_test.py): average next-day swing vs all days
    "fomc": ("First Indian session after a US Fed decision", "swings averaged about 7% wider"),
    "rbi": ("RBI policy decision day", "swings averaged about 3% wider"),
    "expiry": ("Last Tuesday of the month (derivatives expiry)", "swings averaged about 5% narrower"),
    "monthend": ("Month-end", "swings averaged about 4% wider"),
}


def results_history(symbols):
    out = {}
    for sym in symbols:
        try:
            out[sym] = [date.fromisoformat(e["resultsDate"]) for e in get(f"/api/results-calendar/{p.quote(sym)}")]
        except Exception:
            out[sym] = []
    return out


def session_events(next_session):
    """Calendar context for the next session (small measured effects - shown as labels, NOT fed to the model)."""
    ns = date.fromisoformat(next_session)
    try:
        ev = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "risk_events.json"), encoding="utf-8"))
    except Exception:
        return []
    out = []
    fomc_india = {date.fromisoformat(x) + timedelta(days=1) for x in ev["fomc_decision_us_date"]}
    if ns in fomc_india or (ns.weekday() == 0 and ns - timedelta(days=2) in fomc_india):
        out.append("fomc")
    if ns in {date.fromisoformat(x) for x in ev["rbi_decision_date"]}:
        out.append("rbi")
    if ns.weekday() == 1 and (ns + timedelta(days=7)).month != ns.month:
        out.append("expiry")
    if (ns + timedelta(days=3)).month != ns.month:
        out.append("monthend")
    return [{"id": k, "label": EVENT_EFFECTS[k][0], "effect": EVENT_EFFECTS[k][1]} for k in out]


IV_DEADLINE = None          # set from --wait-iv-minutes: how long to wait for tonight's NSE F&O bhavcopy before going without it


def compute_risk(stocks, session, next_session):
    """Per-stock next-session risk forecast (risk_forecast.py) + a market-level summary. Never raises: on any failure the
    screen is still built, just without the risk section."""
    try:
        import risk_forecast as rf
        frames = {s["symbol"]: s["_frame"] for s in stocks}
        d = rf.prepare(frames, results_history(list(frames)))
        ts = pd.Timestamp(session)
        iv_hist = pd.DataFrame(columns=["date", "sym", "iv30"])
        try:                                          # option-implied volatility (NSE F&O bhavcopy); any failure just means "no IV tonight"
            import iv_live
            iv_hist = iv_live.top_up(iv_live.load_history(), frames, session, IV_DEADLINE, log=print)
            d = iv_live.attach(d, iv_hist)
        except Exception as e:  # noqa: BLE001
            print(f"  implied vol unavailable tonight: {e!r}")
        train = d[d["tradingDate"] < ts]
        models = rf.fit(train)
        rows = d[d["tradingDate"] == ts].dropna(subset=rf.FEATS).reset_index(drop=True)
        mean, q90, big = rf.predict(models, rows)
        iv_on = np.zeros(len(rows), dtype=bool)
        if "iv30" in rows.columns:
            iv_cols = ["iv30", "iv_d", "iv_rel", "iv_chg"]
            have = rows[iv_cols].notna().all(axis=1).to_numpy()
            tr_iv = train.dropna(subset=iv_cols)
            if have.sum() >= 30 and len(tr_iv) >= 5000:
                iv_models = rf.fit(tr_iv, rf.FEATS + iv_cols)
                # Implied vol sharpens the EXPECTED range (walk-forward, 600 days: paired t = +7), but it made the 90% band too tight
                # (88.0% vs 89.6%) and did not help the big-move probability - so only the expected range uses it.
                m2 = rf.predict(iv_models, rows[have])[0]
                mean = mean.copy()
                mean[have] = m2
                q90 = np.maximum(q90, mean)
                iv_on = have
        print(f"  risk model: implied-vol version for {int(iv_on.sum())} of {len(rows)} stocks (IV history to {iv_hist['date'].max() if len(iv_hist) else 'n/a'})")
        by = {}
        for i, r in rows.iterrows():
            prob = 100 * float(big[i])
            tier = next(label for thr, label in RISK_TIERS if prob >= thr)
            by[r["instrument"]] = {"expectedRangePct": rnd(mean[i]), "upper90RangePct": rnd(q90[i]), "bigMoveProbPct": rnd(prob, 0),
                                   "typicalRangePct": rnd(r["r20"]), "vsTypicalX": rnd(mean[i] / r["r20"]), "tier": tier,
                                   "resultsNext": bool(r["results_next"]), "ivUsed": bool(iv_on[i]),
                                   "impliedVolPct": rnd(r["iv30"], 1) if iv_on[i] else None}
        try:
            validation = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "risk_validation.json"), encoding="utf-8"))
        except Exception:
            validation = None
        vs = [v["vsTypicalX"] for v in by.values()]
        summary = {"nextSession": next_session, "basketExpectedRangePct": rnd(float(np.mean([v["expectedRangePct"] for v in by.values()]))),
                   "basketTypicalRangePct": rnd(float(np.mean([v["typicalRangePct"] for v in by.values()]))),
                   "basketVsTypicalX": rnd(float(np.mean(vs))), "resultsNext": [k for k, v in by.items() if v["resultsNext"]],
                   "tierCounts": pd.Series([v["tier"] for v in by.values()]).value_counts().to_dict(),
                   "events": session_events(next_session), "validation": validation, "impliedVolStocks": int(sum(v["ivUsed"] for v in by.values())),
                   "note": "Forecasts how BIG tomorrow's swing is (high-low, % of open) - not which way price goes. Big move = close-to-close of 2% or more; over 600 test days the probabilities tracked what actually happened closely at the top (35.8% predicted vs 36.1% observed) and ran about 1-2 points low at the bottom, so read them as approximate."}
        return by, summary
    except Exception as e:  # noqa: BLE001
        print(f"  risk forecast skipped: {e!r}")
        return {}, None


def next_business_day(d):
    d = d + timedelta(days=1)
    while d.weekday() >= 5:
        d += timedelta(days=1)
    return d


def make_hypotheses(stocks, session):
    by_rs = sorted(stocks, key=lambda s: s["rsNifty"], reverse=True)
    out = []
    reporters = [s["symbol"] for s in stocks if s["resultsDate"] == session]
    if reporters:
        out.append({"id": "H1", "title": "Results reaction is bigger than a normal day", "names": reporters, "scoreAfterSessions": 1,
                    "claim": f"{', '.join(reporters)} (vendor results date = today) move at least {RESULTS_REACTION_MOVE:.1f}% close-to-close next session. Direction is NOT claimed.",
                    "evidence": "342 past results events on this basket: reaction-day average |move| 2.38% vs 1.15% on a normal day; 66% react the next session.",
                    "passIf": "at least half of the names move >= 2.0%", "baseRate": "most names on an ordinary day would not move 2%"})
    out.append({"id": "H2", "title": "Short-term reversal of today's relative winners and losers", "scoreAfterSessions": 1,
                "winners": [s["symbol"] for s in by_rs[:5]], "losers": [s["symbol"] for s in by_rs[-5:]],
                "claim": "Today's 5 relative winners underperform today's 5 relative losers next session (market-adjusted close-to-close, equal weight).",
                "evidence": "Next-day rank IC of the day's return = -0.038 (t=-3.8, 512 days): a real but tiny reversal; gross decile spread ~0.02%/day, below trading costs.",
                "passIf": "winners' average market-adjusted return < losers'", "baseRate": "50% by chance; expect ~55%"})
    over = [s["symbol"] for s in stocks if s["rsi"] is not None and s["rsi"] < 30]
    if len(over) >= 3:
        out.append({"id": "H3", "title": "Oversold stocks bounce versus the basket", "names": over, "scoreAfterSessions": 3,
                    "claim": f"The {len(over)} stocks with RSI<30 ({', '.join(over)}) beat the basket on average over the next 3 sessions.",
                    "evidence": "Live RSI-oversold reversal signals right 54% of the time (n=816), in a falling market; offline RSI backtests found no usable edge. Treat as a coin flip with a slight lean.",
                    "passIf": "average market-adjusted return over 3 sessions > 0", "baseRate": "50% by chance"})
    topv = [s["symbol"] for s in sorted([s for s in stocks if s["volRatio"] is not None], key=lambda s: s["volRatio"], reverse=True)[:10]]
    out.append({"id": "H4", "title": "Relative-volume clustering persists", "names": topv, "scoreAfterSessions": 1,
                "claim": "At least 4 of today's 10 highest relative-volume stocks are again in the top 10 next session.",
                "evidence": "23,570 stock-days: yesterday's top-20% relative-volume stocks were top-20% again 40% of the time vs a 21% baseline (t=+66).",
                "passIf": ">= 4 of 10 repeat (<= 2 = fail, 3 = inconclusive)", "baseRate": "2 of 10 expected by chance"})
    return out


def evaluate(h, later):
    """Outcome of hypothesis h given the chronologically later screens (oldest first); None if not enough sessions yet."""
    k = h["scoreAfterSessions"]
    if len(later) < k:
        return None
    adj = lambda scr: {s["symbol"]: s["chg"] - scr["market"]["breadth"]["meanChg"] for s in scr["stocks"] if s["chg"] is not None}
    n1 = later[0]
    if h["id"] == "H1":
        chg = {s["symbol"]: s["chg"] for s in n1["stocks"]}
        moves = [abs(chg[n]) for n in h["names"] if chg.get(n) is not None]
        if not moves:
            return {"outcome": "N/A", "detail": "no data"}
        hit = sum(m >= RESULTS_REACTION_MOVE for m in moves)
        return {"outcome": "PASS" if hit >= len(moves) / 2 else "FAIL",
                "detail": ", ".join(f"{n} {chg[n]:+.1f}%" for n in h["names"] if chg.get(n) is not None) + f" -> {hit} of {len(moves)} moved >= {RESULTS_REACTION_MOVE:.1f}%"}
    if h["id"] == "H2":
        a = adj(n1)
        w, l = np.mean([a[s] for s in h["winners"] if s in a]), np.mean([a[s] for s in h["losers"] if s in a])
        return {"outcome": "PASS" if w < l else "FAIL", "detail": f"winners {w:+.2f}% vs losers {l:+.2f}% (market-adjusted)"}
    if h["id"] == "H3":
        tot = {n: 0.0 for n in h["names"]}
        for scr in later[:3]:
            a = adj(scr)
            for n in tot:
                tot[n] += a.get(n, 0.0)
        m = float(np.mean(list(tot.values())))
        return {"outcome": "PASS" if m > 0 else "FAIL", "detail": f"average 3-session market-adjusted return {m:+.2f}%"}
    if h["id"] == "H4":
        topn = {s["symbol"] for s in sorted([s for s in n1["stocks"] if s["volRatio"] is not None], key=lambda s: s["volRatio"], reverse=True)[:10]}
        rep = len(topn & set(h["names"]))
        return {"outcome": "PASS" if rep >= 4 else "FAIL" if rep <= 2 else "INCONCLUSIVE", "detail": f"{rep} of 10 repeated in the next session's top 10"}
    return None


def score_history(prev, current):
    """prev: stored screens newest-first (not including today's). Returns (scored for the latest prev screen, running tally)."""
    chrono = list(reversed(prev)) + [current]
    tally, latest = {}, None
    for i, scr in enumerate(chrono[:-1]):
        res = []
        for h in scr.get("hypotheses", []):
            r = evaluate(h, chrono[i + 1:])
            if r is None:
                res.append({"id": h["id"], "title": h["title"], "claim": h["claim"], "outcome": "PENDING", "detail": f"scored after {h['scoreAfterSessions']} session(s)"})
                continue
            res.append({"id": h["id"], "title": h["title"], "claim": h["claim"], **r})
            t = tally.setdefault(h["id"], {"title": h["title"], "pass": 0, "fail": 0, "inconclusive": 0})
            t[{"PASS": "pass", "FAIL": "fail"}.get(r["outcome"], "inconclusive")] += 1
        latest = {"forSession": chrono[i + 1]["sessionDate"] if i + 1 < len(chrono) else None, "madeOn": scr["sessionDate"], "results": res}
    return latest, tally


def build(prev_screens):
    now = datetime.now(IST)
    basket = {r["symbol"]: r for r in get("/api/monitor/basket")}
    ts = basket["NIFTY"]["lastCandleTs"]
    session = (datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone(IST)).date().isoformat()
    if ts[11:16] != FINAL_CANDLE_UTC:
        raise SystemExit(f"final candle not in yet (NIFTY last candle {ts})")
    syms = [s for s in basket if s not in ("NIFTY", "BANKNIFTY")]
    cal = {e["instrument"]: e["resultsDate"] for e in get("/api/results-calendar/upcoming?days=10")}
    idx = {}
    for s in ("NIFTY", "BANKNIFTY"):
        d = load_daily(s)
        d = d[d["tradingDate"] < session].reset_index(drop=True)
        close = float(basket[s]["lastClose"])
        c = pd.concat([d["close"], pd.Series([close])], ignore_index=True)
        idx[s] = {"close": rnd(close), "chg": rnd((close / d.iloc[-1]["close"] - 1) * 100), "rsi": rnd(rsi14(c), 0),
                  "r5": rnd((close / d["close"].iloc[-5] - 1) * 100), "r20": rnd((close / d["close"].iloc[-20] - 1) * 100),
                  "vsEma21Pct": rnd((close / float(c.ewm(span=21, adjust=False).mean().iloc[-1]) - 1) * 100),
                  "vsEma200Pct": rnd((close / float(c.ewm(span=200, adjust=False).mean().iloc[-1]) - 1) * 100)}
    stocks = []
    for s in syms:
        stocks.append(stock_record(s, session, basket[s]["lastClose"], idx["NIFTY"]["chg"], cal.get(s)))
    next_session = next_business_day(date.fromisoformat(session)).isoformat()
    risk_by, risk_summary = compute_risk(stocks, session, next_session)
    for s_ in stocks:
        s_.pop("_frame", None)
        if s_["symbol"] in risk_by:
            s_["risk"] = risk_by[s_["symbol"]]
    chgs = [s["chg"] for s in stocks]
    adv = int(sum(c > 0 for c in chgs))
    gm = get("/api/global-market/latest")
    lv = lambda s, k: s["levels"][k]["price"]
    top = lambda key, rev, n=6, cond=lambda s: True: [s["symbol"] for s in sorted([s for s in stocks if cond(s) and s[key] is not None], key=lambda s: s[key], reverse=rev)[:n]]
    lists = {
        "strength": top("rsNifty", True), "weakness": top("rsNifty", False), "volume": top("volRatio", True),
        "breakout20d": [s["symbol"] for s in stocks if s["close"] >= lv(s, "high20d")],
        "breakdown20d": [s["symbol"] for s in sorted(stocks, key=lambda s: s["levels"]["low20d"]["distPct"], reverse=True) if s["close"] <= lv(s, "low20d")],
        "rsiExtremes": [s["symbol"] for s in sorted(stocks, key=lambda s: s["rsi"]) if s["rsi"] is not None and (s["rsi"] < 30 or s["rsi"] > 70)],
        "resultsSoon": sorted([s["symbol"] for s in stocks if s["resultsDate"]], key=lambda n: next(x["resultsDate"] for x in stocks if x["symbol"] == n)),
    }
    market = {"nifty": idx["NIFTY"], "banknifty": idx["BANKNIFTY"],
              "breadth": {"advancers": adv, "decliners": len(stocks) - adv, "meanChg": rnd(np.mean(chgs)), "medianChg": rnd(np.median(chgs)),
                          "aboveEma21": int(sum(s["close"] > s["levels"]["ema21"]["price"] for s in stocks)),
                          "aboveEma200": int(sum(s["close"] > s["levels"]["ema200"]["price"] for s in stocks)),
                          "trendStack": pd.Series([s["trend"] for s in stocks]).value_counts().to_dict()},
              "global": [{"symbol": x["symbol"], "price": x["price"], "changePct": x["changePct"], "date": x["tradingDate"]} for x in gm]}
    screen = {
        "sessionDate": session, "nextSession": next_business_day(date.fromisoformat(session)).isoformat(), "asOf": now.isoformat(timespec="minutes"),
        "dataNote": "Built from the final 15:15 hourly candle and completed daily bars. Today's stored daily row is a mid-morning snapshot and is not used.",
        "notAvailable": ["sector indices", "Asian markets", "gold", "FII/DII flows", "live news", "intraday VWAP"],
        "disclaimer": "Descriptive statistics from your own data - not recommendations. No screened attribute has been shown to predict returns on this basket.",
        "market": market, "lists": lists, "stocks": stocks, "hypotheses": make_hypotheses(stocks, session), "risk": risk_summary,
    }
    prev = [x for x in prev_screens if x.get("sessionDate") != session]
    scored, tally = score_history(prev, screen)
    screen["scoredPrevious"], screen["tally"] = scored, tally
    try:                                                   # the app's daily verdict (verdict.py); never allowed to break the screen
        import verdict as vd
        from concurrent.futures import ThreadPoolExecutor

        def ai_five_day(sym):
            try:
                return sym, get(f"/api/ai-predictions/{p.quote(sym)}?horizon=FORWARD_5D") or []
            except Exception:
                return sym, []
        with ThreadPoolExecutor(6) as ex:
            ai_rows = dict(ex.map(ai_five_day, [x["symbol"] for x in stocks]))
        verdict, per_stock = vd.build_verdict(screen, prev, ai_rows)
        for st in screen["stocks"]:
            if st["symbol"] in per_stock:
                st["verdict"] = per_stock[st["symbol"]]
        screen["verdict"] = verdict
        v_scored, v_tally = vd.score_verdicts(prev, screen)
        screen["verdictScored"], screen["verdictTally"] = v_scored, v_tally
        print(f"  verdict: {verdict['headline']}")
        print(f"  previous verdict: {v_scored['outcome'] + ' - ' + v_scored['detail'] if v_scored else 'none to score yet'} | tally {v_tally}")
    except Exception as e:  # noqa: BLE001
        print(f"  verdict skipped: {e!r}")
    return screen


def main():
    args = sys.argv[1:]
    wait = int(args[args.index("--wait-minutes") + 1]) if "--wait-minutes" in args else 0
    deadline = time.time() + wait * 60
    global IV_DEADLINE
    if "--wait-iv-minutes" in args:
        IV_DEADLINE = time.time() + int(args[args.index("--wait-iv-minutes") + 1]) * 60
    while True:
        try:
            try:
                prev = get("/api/daily-screen/recent?limit=30") or []
            except urllib.error.HTTPError:
                prev = []                                                  # endpoint not deployed yet / first run
            screen = build(prev)
            break
        except SystemExit as e:
            if time.time() >= deadline:
                raise
            print(f"{e}; waiting...", flush=True)
            time.sleep(120)
    print(f"session {screen['sessionDate']} -> next {screen['nextSession']}: {len(screen['stocks'])} stocks, "
          f"{len(screen['hypotheses'])} hypotheses, payload {len(json.dumps(screen)) // 1024} KB")
    print("scored previous:", json.dumps(screen["scoredPrevious"])[:400] if screen["scoredPrevious"] else None)
    print("tally:", screen["tally"])
    if "--no-push" not in args:
        print("pushed:", post("/api/daily-screen", screen))


if __name__ == "__main__":
    main()
