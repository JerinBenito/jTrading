"""Do NSE corporate announcements move the stock the next session - in SIZE and in DIRECTION?

Data: nse_announcements_basket.json (public NSE corporate-announcements feed, 50 basket stocks,
Sep 2024 - Oct 2026; fields s=symbol, t=exchange timestamp IST, d=NSE category, x=headline text).
Caveat stated up front: direction comes from a crude keyword lexicon on the headline, not from reading
the PDF, so it is a rough sentiment proxy.

Clean timing: only announcements made AFTER the close (>= 15:30) or BEFORE the open (< 09:15) are used,
so the effective session is unambiguous (next trading day on/after for after-close; same day for pre-open).
Intraday announcements are dropped (the reaction is split across sessions). Events within 2 business days
of a quarterly-results date are dropped (results were studied separately; they would swamp everything).

Outcomes on the effective session: |close-to-close move| relative to the stock's own trailing-20-day mean
|move|; volume ratio; market-adjusted signed return (stock minus the basket's average that day), split into
the overnight gap and open->close; and the next 5 days after that session.
Significance: events on the same date are correlated (market days), so every confidence statement is a
bootstrap over DATES, not over events.
"""
import json
import os
import re
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from literature_checks import load_daily

ANN, EARN = sys.argv[1], sys.argv[2]
ROUTINE = {"Loss of Share Certificates", "Copy of Newspaper Publication", "Duplicate Share Certificate",
           "Trading Window", "Certificate under SEBI (Depositories and Participants) Regulations, 2018",
           "Compliances-Reg. 74 (5) of SEBI (DP) Reg, 2018", "Statement of Investor Complaints",
           "Reg. 24(A)- Annual Secretarial Compliance", "Shareholders meeting", "Record Date"}
POS = re.compile(r"\b(bagg?ed|wins?|won|awarded|order[s]? (worth|valued|of)|new order|contract|secur(ed|es)|approv(al|ed)|launch(es|ed)?|"
                 r"expan(sion|d)|commission(ed|ing)|buyback|bonus|acqui(re|res|sition|red)|partnership|upgrade[d]?|record|inaugurat)", re.I)
NEG = re.compile(r"(resign|cessation|penalt|\bfine[ds]?\b|show cause|investigation|search|raid|fraud|default|downgrad|litigation|"
                 r"suspen[sd]|shut ?down|fire\b|accident|tax demand|demand (order|notice)|adverse|disallow|cancel|withdraw|"
                 r"recall|strike|lockout|insolven|arrest)", re.I)


def main():
    rows = json.load(open(ANN, encoding="utf-8"))
    ev = pd.DataFrame(rows).rename(columns={"s": "instrument", "t": "ts", "d": "desc", "x": "text"})
    ev["instrument"] = ev["instrument"].replace({"TMPV": "TMPV"})
    ev["ts"] = pd.to_datetime(ev["ts"], format="%d-%b-%Y %H:%M:%S")
    print(f"{len(ev)} announcements, {ev.instrument.nunique()} stocks, {ev.ts.min().date()}..{ev.ts.max().date()}")
    print("top categories:", ev["desc"].value_counts().head(12).to_dict())

    d = load_daily()
    d["instrument"] = d["instrument"].replace({"TATAMOTORS": "TMPV"})
    for c in ("open", "close", "dailyReturnPct", "gapFromPrevClosePct", "volumeRatio20d"):
        d[c] = pd.to_numeric(d[c], errors="coerce")
    d = d.sort_values(["instrument", "tradingDate"]).reset_index(drop=True)
    d["mkt"] = d.groupby("tradingDate")["dailyReturnPct"].transform("mean")
    d["gap_adj"] = d["gapFromPrevClosePct"] - d.groupby("tradingDate")["gapFromPrevClosePct"].transform("mean")
    d["oc"] = (d["close"] / d["open"] - 1) * 100
    d["oc_adj"] = d["oc"] - d.groupby("tradingDate")["oc"].transform("mean")
    d["ret_adj"] = d["dailyReturnPct"] - d["mkt"]
    g = d.groupby("instrument")
    d["abs_ret"] = d["dailyReturnPct"].abs()
    d["typ_abs"] = g["abs_ret"].transform(lambda s: s.rolling(20, min_periods=10).mean().shift(1))
    d["rel_move"] = d["abs_ret"] / d["typ_abs"]
    d["fwd5"] = g["close"].transform(lambda s: s.shift(-5) / s - 1) * 100
    d["fwd5_adj"] = d["fwd5"] - d.groupby("tradingDate")["fwd5"].transform("mean")

    # results dates to exclude
    er = pd.read_csv(EARN)
    er["date"] = pd.to_datetime(er["Earnings Date"].str[:10])
    res_by = {s: sorted(x["date"]) for s, x in er.groupby("instrument")}

    days_by = {s: x["tradingDate"].to_numpy() for s, x in d.groupby("instrument")}
    eff = []
    for r in ev.itertuples():
        mins = r.ts.hour * 60 + r.ts.minute
        if 9 * 60 + 15 <= mins < 15 * 60 + 30:
            eff.append(pd.NaT); continue
        base = r.ts.normalize() if mins < 9 * 60 + 15 else r.ts.normalize() + pd.Timedelta(days=1)
        days = days_by.get(r.instrument)
        if days is None:
            eff.append(pd.NaT); continue
        i = np.searchsorted(days, np.datetime64(base))
        eff.append(pd.Timestamp(days[i]) if i < len(days) else pd.NaT)
    ev["eff"] = eff
    n0 = len(ev)
    ev = ev.dropna(subset=["eff"])
    print(f"{n0 - len(ev)} intraday/unmatched announcements dropped, {len(ev)} clean pre-open/after-close events")

    def near_results(r):
        for e in res_by.get(r.instrument, []):
            if abs((r.eff - e).days) <= 3:
                return True
        return False
    ev["near_res"] = [near_results(r) for r in ev.itertuples()]
    ev = ev[~ev.near_res & ~ev["desc"].isin(ROUTINE) & ~ev["desc"].str.contains("Financial Result", case=False, na=False)]
    print(f"{len(ev)} after dropping results-window and routine-filing categories")

    panel = d.set_index(["instrument", "tradingDate"])
    base_mask = pd.Series(True, index=panel.index)
    ev_key = set(zip(ev.instrument, ev.eff))
    is_ev = pd.Series([k in ev_key for k in panel.index], index=panel.index)
    base = panel[~is_ev]

    def boot_mean(vals, dates, n=2000, seed=0):
        """cluster bootstrap over dates: returns mean, 95% CI"""
        df = pd.DataFrame({"v": vals, "d": dates}).dropna()
        agg = df.groupby("d")["v"].agg(["sum", "count"])
        s, c = agg["sum"].to_numpy(), agg["count"].to_numpy()
        rng = np.random.default_rng(seed)
        idx = rng.integers(0, len(s), (n, len(s)))
        means = s[idx].sum(1) / c[idx].sum(1)
        return df["v"].mean(), np.percentile(means, 2.5), np.percentile(means, 97.5), len(df), len(s)

    # ---------------- A. size
    print("\n=== A. SIZE: does the next session move more than usual after an announcement of this kind? ===")
    print(f"baseline (stock-days with no clean announcement): relative |move| {base['rel_move'].mean():.2f}x own typical, volume ratio {base['volumeRatio20d'].mean():.2f}")
    print(f"{'category':58s} {'n':>5s} {'rel |move|':>11s} {'95% CI (dates)':>16s} {'vol ratio':>9s}")
    cats = ev["desc"].value_counts()
    for cat in cats[cats >= 60].index:
        sub = ev[ev["desc"] == cat].drop_duplicates(["instrument", "eff"])
        x = panel.loc[list(zip(sub.instrument, sub.eff))]
        m, lo, hi, n, nd = boot_mean(x["rel_move"].to_numpy(), x.index.get_level_values(1))
        print(f"{cat[:58]:58s} {n:5d} {m:10.2f}x {lo:7.2f}..{hi:5.2f} {x['volumeRatio20d'].mean():9.2f}")
    allx = panel.loc[list(zip(*[ev.drop_duplicates(['instrument', 'eff'])[c] for c in ('instrument', 'eff')]))]
    m, lo, hi, n, nd = boot_mean(allx["rel_move"].to_numpy(), allx.index.get_level_values(1))
    print(f"{'ANY clean announcement (stock-days)':58s} {n:5d} {m:10.2f}x {lo:7.2f}..{hi:5.2f} {allx['volumeRatio20d'].mean():9.2f}")
    k = ev.groupby(["instrument", "eff"]).size()
    multi = panel.loc[list(k[k >= 3].index)]
    m, lo, hi, n, nd = boot_mean(multi["rel_move"].to_numpy(), multi.index.get_level_values(1))
    print(f"{'3+ announcements the same night (stock-days)':58s} {n:5d} {m:10.2f}x {lo:7.2f}..{hi:5.2f} {multi['volumeRatio20d'].mean():9.2f}")

    # ---------------- B. direction
    print("\n=== B. DIRECTION: keyword-signed headline -> next-session market-adjusted return (stock minus basket average) ===")
    ev["pos"] = ev["text"].fillna("").str.contains(POS)
    ev["neg"] = ev["text"].fillna("").str.contains(NEG)
    ev["cls"] = np.where(ev.pos & ~ev.neg, "positive", np.where(ev.neg & ~ev.pos, "negative", "other"))
    print(ev["cls"].value_counts().to_dict())
    print(f"{'class':10s} {'n':>5s} | {'gap (adj)':>18s} | {'open->close (adj)':>20s} | {'whole day (adj)':>18s} | {'next 5d after (adj)':>20s}")
    for cls in ("positive", "negative"):
        sub = ev[ev.cls == cls].drop_duplicates(["instrument", "eff"])
        x = panel.loc[list(zip(sub.instrument, sub.eff))]
        dates = x.index.get_level_values(1)
        cells = []
        for col in ("gap_adj", "oc_adj", "ret_adj", "fwd5_adj"):
            m, lo, hi, n, nd = boot_mean(x[col].to_numpy(), dates)
            cells.append(f"{m:+.3f} [{lo:+.2f},{hi:+.2f}]")
        print(f"{cls:10s} {n:5d} | " + " | ".join(f"{c:>18s}" for c in cells))
    print("  (a confidence interval that straddles 0 = no demonstrated effect; 4 outcomes x 2 classes = 8 looks, so only clear misses of 0 count)")
    print("\nBy category (positive-lexicon headlines only, whole-day adj. return and next 5d):")
    pos = ev[ev.cls == "positive"]
    for cat in pos["desc"].value_counts().head(6).index:
        sub = pos[pos["desc"] == cat].drop_duplicates(["instrument", "eff"])
        if len(sub) < 30: continue
        x = panel.loc[list(zip(sub.instrument, sub.eff))]
        m, lo, hi, n, nd = boot_mean(x["ret_adj"].to_numpy(), x.index.get_level_values(1))
        m5, lo5, hi5, *_ = boot_mean(x["fwd5_adj"].to_numpy(), x.index.get_level_values(1))
        print(f"  {cat[:50]:50s} n={n:4d} day {m:+.3f} [{lo:+.2f},{hi:+.2f}]   next5d {m5:+.3f} [{lo5:+.2f},{hi5:+.2f}]")


if __name__ == "__main__":
    main()
