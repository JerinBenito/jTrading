"""The app's daily VERDICT: one plain-language read of tomorrow, built from the models and data the system already runs, plus an
honest self-grade the next day.  It judges SIZE (how big a swing to expect, which stocks have events, which are calm), where the
evidence is real, and it says plainly that it has NO reliable view on direction.  The AI model's own 5-day lean is shown with its
live track record so you can see whether it has earned any weight.  Never an entry / stop / target / position size.

  build_verdict(screen, prev_screens, ai_rows)  ->  (verdict dict, {symbol: per-stock verdict})
  score_verdicts(prev_screens_newest_first, current_screen) -> (scoring of the latest previous verdict, running tally)
Confidence tags: "tested" = validated walk-forward on unseen days; "descriptive" = a fact about the data with no demonstrated
predictive value; "none" = no edge (said out loud).
"""
import datetime as dt
import numpy as np
import pandas as pd
from scipy import stats

ATTENTION_GROUPS = ("event", "high")
RESULTS_SOON_SESSIONS = 3


def rnd(x, n=2):
    return None if x is None or (isinstance(x, float) and np.isnan(x)) else round(float(x), n)


def _bdays_after(d, n):
    out = []
    while len(out) < n:
        d += dt.timedelta(days=1)
        if d.weekday() < 5:
            out.append(d)
    return out


def ai_track_record(ai_rows):
    """Live record of the AI model's 5-day calls across all stocks. ai_rows: {symbol: [prediction dicts, newest first]}."""
    ev = [r for rows in ai_rows.values() for r in rows if r.get("actualValue") is not None and r.get("directionCorrect") is not None]
    if len(ev) < 30:
        return None
    dir_ok = np.mean([bool(r["directionCorrect"]) for r in ev])
    better = np.mean([bool(r["betterThanBaseline"]) for r in ev if r.get("betterThanBaseline") is not None])
    df = pd.DataFrame([{"d": r["targetDate"], "lean": float(r["predictedValue"]) - float(r["baselineValue"]),
                        "out": float(r["actualValue"]) - float(r["baselineValue"])} for r in ev])
    ics = [stats.spearmanr(g.lean, g.out)[0] for _, g in df.groupby("d") if len(g) >= 15 and g.lean.nunique() > 1]
    ics = [x for x in ics if not np.isnan(x)]
    ic = float(np.mean(ics)) if ics else None
    t = float(np.mean(ics) / (np.std(ics, ddof=1) / np.sqrt(len(ics)))) if len(ics) >= 5 and np.std(ics, ddof=1) > 0 else None
    return {"calls": len(ev), "directionRightPct": rnd(100 * dir_ok, 0), "beatBaselinePct": rnd(100 * better, 0),
            "rankCorrelation": rnd(ic, 2), "rankCorrelationT": rnd(t, 1), "days": len(ics)}


def _stock_line(label, r, res_note):
    exp, q90, big, vsx = r.get("expectedRangePct"), r.get("upper90RangePct"), r.get("bigMoveProbPct"), r.get("vsTypicalX")
    base = f"expect a swing of about {exp:.1f}% high to low ({vsx:.2f}x its usual), up to ~{q90:.1f}% on a rough day; about a {big:.0f}% chance of a 2%+ close-to-close move"
    if label == "Event watch":
        return f"{res_note}: {base}."
    if label == "High swing":
        return f"Bigger-than-usual swing risk: {base}."
    if label == "Calm":
        return f"Calmer than most: {base}."
    if label == "Stretched":
        return f"Stretched on the chart: {base}."
    return f"Normal day: {base}."


def build_verdict(screen, prev_screens, ai_rows):
    """screen: the screen being built (stocks with risk, market, risk summary). Returns (verdict, per_stock)."""
    session = dt.date.fromisoformat(screen["sessionDate"])
    nxt = dt.date.fromisoformat(screen["nextSession"])
    soon = set(_bdays_after(nxt, RESULTS_SOON_SESSIONS))
    risk = screen.get("risk") or {}
    stocks = screen["stocks"]
    per, groups = {}, {"event": [], "soon": [], "high": [], "stretched": [], "calm": [], "normal": []}
    exps = [s["risk"]["expectedRangePct"] for s in stocks if s.get("risk")]
    for s in stocks:
        r = s.get("risk")
        if not r:
            continue
        rd = dt.date.fromisoformat(s["resultsDate"]) if s.get("resultsDate") else None
        reasons = []
        res_note = None
        if rd is not None and rd in (session, nxt):
            label = "Event watch"
            res_note = "Reacting to results announced today" if rd == session else "Reports results tomorrow"
            reasons.append("results after the close today, so the market reacts tomorrow" if rd == session else "reports results during tomorrow's session")
            groups["event"].append(s["symbol"])
        elif r["tier"] == "High":
            label = "High swing"
            groups["high"].append(s["symbol"])
        elif s.get("rsi") is not None and (s["rsi"] < 32 or s["rsi"] > 68):
            label = "Stretched"
            groups["stretched"].append(s["symbol"])
        elif r["tier"] == "Normal" and not (rd is not None and rd in soon):
            label = "Calm"
            groups["calm"].append(s["symbol"])
        else:
            label = "Normal"
            groups["normal"].append(s["symbol"])
        if rd is not None and rd in soon and label not in ("Event watch",):
            reasons.append(f"results due {rd:%d %b}")
            groups["soon"].append(s["symbol"])
        if r.get("ivUsed") and r.get("impliedVolPct") is not None:
            reasons.append(f"options market prices about {r['impliedVolPct']:.0f}% volatility a year")
        if r["vsTypicalX"] >= 1.3:
            reasons.append(f"expected swing is {r['vsTypicalX']:.2f}x its usual")
        if s.get("rsi") is not None and s["rsi"] < 32:
            reasons.append(f"oversold (RSI {s['rsi']:.0f}, {s['r20']:+.1f}% in 20 days): bigger swings possible, no proven bounce")
        if s.get("rsi") is not None and s["rsi"] > 68:
            reasons.append(f"overbought (RSI {s['rsi']:.0f}, {s['r5']:+.1f}% in 5 days): bigger swings possible, no proven pullback")
        line = _stock_line(label, r, res_note)
        ai = ai_rows.get(s["symbol"]) or []
        lean = None
        if ai:
            a = ai[0]
            gap = float(a["predictedValue"]) - float(a["baselineValue"])
            lean = {"fiveDayPct": rnd(float(a["predictedValue"]), 2), "usualPct": rnd(float(a["baselineValue"]), 2), "gapPct": rnd(gap, 2)}
        per[s["symbol"]] = {"label": label, "line": line, "reasons": reasons, "aiLean": lean}
    # AI ranking: only the extremes, and only as a labelled, unproven view
    leans = sorted(((sym, v["aiLean"]["gapPct"]) for sym, v in per.items() if v["aiLean"]), key=lambda x: x[1])
    track = ai_track_record(ai_rows)

    b = screen["market"]["breadth"]
    nifty = screen["market"]["nifty"]
    ts = b.get("trendStack", {})
    exp_b, typ_b, vsx = risk.get("basketExpectedRangePct"), risk.get("basketTypicalRangePct"), risk.get("basketVsTypicalX")
    events = risk.get("events") or []
    lines = []
    wide = vsx is not None and vsx >= 1.1
    size_txt = f"The basket should swing about {exp_b:.2f}% high to low tomorrow against a usual {typ_b:.2f}% ({vsx:.2f}x)."
    if events:
        size_txt += " " + " ".join(f"{e['label']} ({e['effect']})." for e in events) + " The model does not include these, so lean toward the upper end."
    lines.append({"text": size_txt, "confidence": "tested", "tag": "Size of moves"})
    n_event = len(groups["event"])
    if n_event:
        lines.append({"text": f"{n_event} stock(s) have a results reaction in tomorrow's session: {', '.join(groups['event'])}. Results days have moved stocks about twice as much as normal days.",
                      "confidence": "tested", "tag": "Results"})
    if groups["soon"]:
        lines.append({"text": f"Results also due within the next {RESULTS_SOON_SESSIONS} sessions: {', '.join(groups['soon'])}.", "confidence": "tested", "tag": "Results"})
    lines.append({"text": f"Backdrop: {ts.get('down', 0)} of {len(stocks)} stocks are in downtrends and {b.get('aboveEma200', 0)} are above their 200-day average; NIFTY RSI {nifty.get('rsi')}; "
                          f"{b['advancers']} up / {b['decliners']} down today. This describes the mood; it has not been shown to predict the next day's direction.",
                  "confidence": "descriptive", "tag": "Backdrop"})
    gl = {g["symbol"]: g["changePct"] for g in screen["market"].get("global", [])}
    if gl:
        names = {"SP500": "S&P 500", "DOW": "Dow", "NASDAQ": "Nasdaq", "CRUDE_OIL": "Crude oil", "USD_INR": "USD/INR", "INDIA_VIX": "India VIX"}
        lines.append({"text": "Overnight context: " + ", ".join(f"{names.get(k, k)} {v:+.1f}%" for k, v in gl.items() if v is not None) +
                      ". Tested: overseas moves add nothing to the size forecast.", "confidence": "descriptive", "tag": "Global"})
    if track:
        t_ = track.get("rankCorrelationT")
        sig = "not statistically significant" if t_ is None or abs(t_) < 2 else "statistically detectable but small"
        lines.append({"text": f"Direction: no reliable view. The AI model's own 5-day calls have been right {track['directionRightPct']:.0f}% of the time over {track['calls']} scored calls "
                               f"(a coin flip is 50%) and beat the plain baseline {track['beatBaselinePct']:.0f}% of the time; its stock-ranking correlation with outcomes is "
                               f"{track['rankCorrelation']} ({sig}, over {track['days']} days). Calls made on the same day move together, so these are far fewer independent tests than the "
                               f"call count suggests. Treat any lean below as unproven.", "confidence": "none", "tag": "Direction"})
    else:
        lines.append({"text": "Direction: no reliable view. No direction signal has survived testing, and the AI model's 5-day calls have too short a record to judge.", "confidence": "none", "tag": "Direction"})

    ev_names = {"fomc": "Fed-decision morning", "monthend": "month-end", "expiry": "expiry day", "rbi": "RBI decision day"}
    ev_txt = " and ".join(ev_names.get(e["id"], e["id"]) for e in events)
    kind = "wider than usual" if wide else (f"a normal day or a little wider ({ev_txt})" if events else "close to a normal day")
    headline = (f"Tomorrow should be {kind} for swings ({exp_b:.1f}% vs {typ_b:.1f}% usual); {n_event} results reaction(s), {len(groups['high'])} high-swing and "
                f"{len(groups['calm'])} calm names. No reliable call on direction.")
    gdef = [("event", "Results reaction", "Expect the biggest moves: results days have moved stocks about twice as much as normal.", "tested"),
            ("high", "High swing", "The model expects a larger-than-usual swing here.", "tested"),
            ("stretched", "Stretched", "Oversold or overbought on RSI: bigger swings are possible; no proven bounce or pullback.", "descriptive"),
            ("calm", "Calmer than most", "The model expects the smallest swings here.", "tested")]
    out_groups = [{"id": gid, "title": t, "why": why, "confidence": conf, "names": groups[gid]} for gid, t, why, conf in gdef if groups[gid]]
    verdict = {
        "forSession": screen["nextSession"], "headline": headline, "lines": lines, "groups": out_groups,
        "membership": {k: v for k, v in groups.items() if k in ("event", "high", "calm", "stretched")},
        "forecastRange": {s["symbol"]: s["risk"]["expectedRangePct"] for s in stocks if s.get("risk")},
        "ai": {"track": track, "lowestLean": [{"symbol": s, "gapPct": g} for s, g in leans[:5]], "highestLean": [{"symbol": s, "gapPct": g} for s, g in leans[::-1][:5]],
               "note": "The AI model's 5-day lean versus its own baseline. Shown for transparency: its record so far does not show skill."},
        "disclaimer": "Descriptive judgement from the app's models. Not a recommendation: no entries, stops, targets or position sizes. Direction is not predicted.",
    }
    return verdict, per


def score_verdicts(prev_newest_first, current):
    """Scores each earlier verdict against the session it was for (the next stored screen) and keeps a running tally.
    A verdict PASSES when the stocks it flagged (results reaction + high swing) swung at least 15% more than the ones it called calm."""
    chrono = list(reversed(prev_newest_first)) + [current]
    tally = {"pass": 0, "fail": 0, "inconclusive": 0}
    latest = None
    for i, scr in enumerate(chrono[:-1]):
        v = scr.get("verdict")
        nxt = chrono[i + 1]
        if not v or nxt.get("sessionDate") != v.get("forSession"):
            continue
        rng = {s["symbol"]: s.get("rangePct") for s in nxt["stocks"] if s.get("rangePct") is not None}
        chg = {s["symbol"]: abs(s["chg"]) for s in nxt["stocks"]}
        mem = v["membership"]
        att = [x for g in ATTENTION_GROUPS for x in mem.get(g, []) if x in rng]
        calm = [x for x in mem.get("calm", []) if x in rng]
        fc = v.get("forecastRange", {})
        if len(att) < 3 or len(calm) < 3:
            outcome, detail = "INCONCLUSIVE", "too few stocks in a group to compare"
            ratio = None
        else:
            a, c = float(np.mean([rng[x] for x in att])), float(np.mean([rng[x] for x in calm]))
            ratio = a / c if c > 0 else None
            outcome = "PASS" if ratio is not None and ratio >= 1.15 else "FAIL"
            detail = f"flagged stocks swung {a:.2f}% on average vs {c:.2f}% for the calm ones ({ratio:.2f}x; needed 1.15x)"
        ev_names = [x for x in mem.get("event", []) if x in chg]
        extra = {}
        if ev_names:
            rest = [v_ for k, v_ in chg.items() if k not in ev_names]
            extra["resultsMove"] = {"names": ev_names, "avgAbsMovePct": rnd(float(np.mean([chg[x] for x in ev_names])), 2), "restAvgAbsMovePct": rnd(float(np.mean(rest)), 2)}
        both = [x for x in fc if x in rng]
        bias = None
        if len(both) >= 10:
            bias = rnd(float(np.mean([fc[x] for x in both]) / np.mean([rng[x] for x in both]) - 1) * 100, 0)
        tally[{"PASS": "pass", "FAIL": "fail"}.get(outcome, "inconclusive")] += 1
        latest = {"forSession": v["forSession"], "madeOn": scr["sessionDate"], "outcome": outcome, "detail": detail, "ratio": rnd(ratio, 2),
                  "forecastVsActualRangePct": bias, **extra}
    return latest, tally
