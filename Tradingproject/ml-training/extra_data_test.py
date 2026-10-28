"""Data audit (2026-10-28): does any free extra data improve the NEXT-DAY RANGE model beyond the production model
(risk_forecast FEATS + implied-vol features)?  Walk-forward, last N_TEST days in 40-day folds, day = unit, identical hyperparameters,
every variant scored on the SAME rows (rows with the production inputs; the candidate columns may be NaN - LightGBM handles that).
  +FO     put/call OI ratio, futures OI change, futures basis, put-call skew, vol term slope        (NSE F&O bhavcopy, already held)
  +SEC    average range of the stock's sector peers today and over 5 days                           (our own 50 stocks)
  +OVR    Asian markets' same-day moves, previous US session's move and US VIX                       (Yahoo)
  +FLOW   FII positioning in index / stock futures, previous day                                    (NSE participant OI)
  +EV     next day is a Fed-decision morning / Budget / election-result day                          (Fed calendar verified; Budget + 4 Jun 2024)
  +ALL    everything above
  NSE-RESULTS: production model fed with NSE board-meeting dates instead of Yahoo's results dates, same rows.
Also reports how accurate Yahoo's historical results dates were against NSE's.   Usage: python extra_data_test.py <earnings_dates.csv>
"""
import os, sys
import numpy as np, pandas as pd
from lightgbm import LGBMRegressor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import risk_forecast as rf
import iv_live
import multiday_volume_ablation as mv

HERE = os.path.dirname(os.path.abspath(__file__))
N_TEST = int(os.environ.get("N_TEST", 600))
X = os.path.join(HERE, "data", "extra")

SECTORS = {"BANK": ["HDFCBANK", "ICICIBANK", "AXISBANK", "KOTAKBANK", "SBIN"],
           "FIN": ["BAJFINANCE", "BAJAJFINSV", "SHRIRAMFIN", "JIOFIN", "HDFCLIFE", "SBILIFE"],
           "IT": ["TCS", "INFY", "HCLTECH", "WIPRO", "TECHM"],
           "AUTO": ["MARUTI", "M&M", "BAJAJ-AUTO", "EICHERMOT", "TMPV"],
           "METAL": ["TATASTEEL", "JSWSTEEL", "HINDALCO", "COALINDIA"],
           "ENERGY": ["RELIANCE", "ONGC", "NTPC", "POWERGRID", "ADANIENT", "ADANIPORTS"],
           "FMCG": ["HINDUNILVR", "ITC", "NESTLEIND", "TATACONSUM", "ASIANPAINT", "TITAN"],
           "HEALTH": ["SUNPHARMA", "CIPLA", "DRREDDY", "APOLLOHOSP", "MAXHEALTH"],
           "INFRA": ["LT", "ULTRACEMCO", "GRASIM", "BEL"],
           "CONSUMER": ["TRENT", "ETERNAL", "INDIGO", "BHARTIARTL"]}
SEC_OF = {s: k for k, v in SECTORS.items() for s in v}

raw = mv.build_frame(); raw["tradingDate"] = pd.to_datetime(raw["tradingDate"])
raw = raw[~raw["instrument"].isin(["NIFTY", "BANKNIFTY"])]
for c in ("open", "high", "low", "close", "volumeRatio20d", "gapFromPrevClosePct"):
    raw[c] = pd.to_numeric(raw[c], errors="coerce")
raw["range"] = (raw["high"] - raw["low"]) / raw["open"] * 100; raw["gap"] = raw["gapFromPrevClosePct"]
frames = {s: g[["tradingDate", "open", "close", "range", "volumeRatio20d", "gap"]] for s, g in raw.groupby("instrument")}

# ---- results dates: Yahoo (production) vs NSE board meetings
ev = pd.read_csv(sys.argv[1]); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]); ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
res_yahoo = {s: list(x["date"].dt.date) for s, x in ev.groupby("instrument")}
bm = pd.read_csv(os.path.join(X, "nse_board_meetings.csv"))
bm = bm[bm["bm_purpose"].str.contains("financial result", case=False, na=False) | bm["bm_desc"].str.contains("financial result", case=False, na=False)].copy()
bm["date"] = pd.to_datetime(bm["bm_date"], format="%d-%b-%Y", errors="coerce")
bm = bm.dropna(subset=["date"]).drop_duplicates(["symbol", "date"])
res_nse = {s: list(x["date"].dt.date) for s, x in bm.groupby("symbol")}
tot = hit0 = hit1 = ymiss = 0
for s, ys in res_yahoo.items():
    ns = res_nse.get(s, [])
    for y in ys:
        if y < pd.Timestamp("2022-03-01").date() or y > pd.Timestamp("2026-10-20").date():
            continue
        tot += 1
        dmin = min((abs((y - n).days) for n in ns), default=99)
        hit0 += dmin == 0; hit1 += dmin <= 1; ymiss += dmin > 3
print(f"Yahoo results dates vs NSE board meetings (2022-03..2026-10, {tot} Yahoo dates): exact {100 * hit0 / tot:.0f}% | within 1 day {100 * hit1 / tot:.0f}% | no NSE meeting within 3 days {100 * ymiss / tot:.0f}%")
nse_only = sum(1 for s, ns in res_nse.items() for n in ns if pd.Timestamp("2022-03-01").date() <= n <= pd.Timestamp("2026-10-20").date() and min((abs((n - y).days) for y in res_yahoo.get(s, [])), default=99) > 3)
print(f"NSE results meetings with no Yahoo date within 3 days: {nse_only} of {sum(1 for s, ns in res_nse.items() for n in ns if pd.Timestamp('2022-03-01').date() <= n <= pd.Timestamp('2026-10-20').date())}", flush=True)

d = rf.prepare(frames, res_yahoo)
d2 = rf.prepare(frames, res_nse)[["instrument", "tradingDate", "results_next"]].rename(columns={"results_next": "results_next_nse"})
d = d.merge(d2, on=["instrument", "tradingDate"], how="left")
d = iv_live.attach(d, iv_live.load_history())
PROD = rf.FEATS + iv_live.IV4

# ---- +FO
fo = pd.read_csv(os.path.join(HERE, "data", "fo", "extras_daily.csv"), parse_dates=["date"]).rename(columns={"sym": "instrument", "date": "tradingDate"})
FO = ["pcr_oi", "fut_oi_chg", "basis", "skew", "term"]
d = d.merge(fo[["instrument", "tradingDate"] + FO], on=["instrument", "tradingDate"], how="left")
# ---- +SEC (leave-one-out sector peer range)
d["sector"] = d["instrument"].map(SEC_OF)
g = d.groupby(["sector", "tradingDate"])["range"]
n = g.transform("size"); sm = g.transform("sum")
d["sec_range"] = np.where(n > 1, (sm - d["range"]) / (n - 1).clip(lower=1), np.nan)
d["sec_range5"] = d.groupby("instrument")["sec_range"].transform(lambda s: s.rolling(5, min_periods=3).mean())
SEC = ["sec_range", "sec_range5"]
# ---- +OVR
ov = pd.read_csv(os.path.join(X, "overseas.csv"), parse_dates=["date"]).sort_values("date").set_index("date")
asia = pd.concat([(ov[c] / ov[c].shift(1) - 1).abs() * 100 for c in ("nikkei", "hangseng", "kospi")], axis=1).mean(axis=1).rename("asia_abs")
us = pd.DataFrame({"us_abs_prev": (ov["sp500"] / ov["sp500"].shift(1) - 1).abs() * 100, "usvix_prev": ov["usvix"]}).shift(1)   # previous US session
ovr = pd.concat([asia, us], axis=1).reset_index().rename(columns={"date": "tradingDate"})
OVR = ["asia_abs", "us_abs_prev", "usvix_prev"]
d = d.merge(ovr, on="tradingDate", how="left")
# ---- +FLOW (previous day's FII positioning)
po = pd.read_csv(os.path.join(X, "participant_oi.csv"), parse_dates=["date"])
fii = po[po["Client Type"] == "FII"].set_index("date").sort_index()
fl = pd.DataFrame({"fii_idx_net": (fii["Future Index Long"] - fii["Future Index Short"]) / (fii["Future Index Long"] + fii["Future Index Short"]),
                   "fii_stk_net": (fii["Future Stock Long"] - fii["Future Stock Short"]) / (fii["Future Stock Long"] + fii["Future Stock Short"])})
fl["fii_idx_chg"] = fl["fii_idx_net"].diff()
fl = fl.shift(1).reset_index().rename(columns={"date": "tradingDate"})
FLOW = ["fii_idx_net", "fii_stk_net", "fii_idx_chg"]
d = d.merge(fl, on="tradingDate", how="left")
# ---- +EV (the next trading day is the morning after a Fed decision, a Budget, or the 2024 election result)
fomc = ["2022-01-26", "2022-03-16", "2022-05-04", "2022-06-15", "2022-07-27", "2022-09-21", "2022-11-02", "2022-12-14", "2023-02-01", "2023-03-22", "2023-05-03",
        "2023-06-14", "2023-07-26", "2023-09-20", "2023-11-01", "2023-12-13", "2024-01-31", "2024-03-20", "2024-05-01", "2024-06-12", "2024-07-31", "2024-09-18",
        "2024-11-07", "2024-12-18", "2025-01-29", "2025-03-19", "2025-05-07", "2025-06-18", "2025-07-30", "2025-09-17", "2025-10-29", "2025-12-10", "2026-01-28",
        "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16", "2026-10-28"]
all_days = pd.DatetimeIndex(sorted(d["tradingDate"].unique()))
def next_td(x):
    i = all_days.searchsorted(pd.Timestamp(x), side="right")
    return all_days[i] if i < len(all_days) else None
events = {next_td(x) for x in fomc} - {None}                                   # India reacts the morning after the US evening decision
events |= {pd.Timestamp(x) for x in ("2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01", "2024-06-04")}
d["ev_next"] = d["next_date"].map(lambda x: 1.0 if pd.Timestamp(x) in events else 0.0)
EV = ["ev_next"]

d = d.dropna(subset=PROD + ["target_range", "target_absret", "results_next_nse"]).reset_index(drop=True)
print(f"{len(d):,} stock-days with the production inputs; {d.tradingDate.min().date()}..{d.tradingDate.max().date()}")
print("candidate column coverage:", {c: f"{100 * d[c].notna().mean():.0f}%" for c in FO + SEC + OVR + FLOW + EV}, flush=True)

if os.environ.get("EV_FOLLOWUP"):
    # which part of the event flag helps, and does it beat random "fake event" days? (same walk-forward, same rows)
    fomc_react = {next_td(x) for x in fomc} - {None}
    india = {pd.Timestamp(x) for x in ("2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01", "2024-06-04")}
    d["ev_fomc"] = d["next_date"].map(lambda x: 1.0 if pd.Timestamp(x) in fomc_react else 0.0)
    d["ev_india"] = d["next_date"].map(lambda x: 1.0 if pd.Timestamp(x) in india else 0.0)
    rng = np.random.default_rng(7)
    pool = [x for x in all_days if x not in fomc_react and x not in india]
    K = int(os.environ.get("PLACEBOS", 20))
    for k in range(K):
        fake = set(rng.choice(pool, size=len(fomc_react) + len(india), replace=False))
        d[f"pl{k}"] = d["next_date"].map(lambda x, f=fake: 1.0 if pd.Timestamp(x) in f else 0.0)
    variants = {"prod": PROD, "+EV": PROD + ["ev_next"], "+FOMC": PROD + ["ev_fomc"], "+INDIA": PROD + ["ev_india"], **{f"pl{k}": PROD + [f"pl{k}"] for k in range(K)}}
    days = sorted(d["tradingDate"].unique()); test_days = days[-N_TEST:]
    out = []
    for i in range(N_TEST // 40):
        fd = test_days[i * 40:(i + 1) * 40]
        tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]; te = d[d["tradingDate"].isin(fd)].copy()
        for name, feats in variants.items():
            te[name] = LGBMRegressor(**rf._PARAMS).fit(tr[feats], tr["target_range"]).predict(te[feats])
        out.append(te)
    r = pd.concat(out, ignore_index=True)
    mae = {n: (r.target_range - r[n]).abs().mean() for n in variants}
    ref = (r.target_range - r["prod"]).abs().groupby(r.tradingDate).mean()
    for n in ("+EV", "+FOMC", "+INDIA"):
        e = (r.target_range - r[n]).abs().groupby(r.tradingDate).mean()
        print(f"{n:7s} MAE {mae[n]:.4f} vs prod {mae['prod']:.4f} ({100 * (1 - mae[n] / mae['prod']):+.2f}%), paired t {(ref - e).mean() / ((ref - e).std(ddof=1) / np.sqrt(len(e))):+.2f}")
    pl = np.array([100 * (1 - mae[f"pl{k}"] / mae["prod"]) for k in range(K)])
    print(f"placebo (random fake event days, same count): mean {pl.mean():+.2f}%  sd {pl.std():.2f}  best {pl.max():+.2f}%  -> real +EV beats {100 * (pl < 100 * (1 - mae['+EV'] / mae['prod'])).mean():.0f}% of placebos, +FOMC beats {100 * (pl < 100 * (1 - mae['+FOMC'] / mae['prod'])).mean():.0f}%")
    on = r[r.ev_next == 1]
    print(f"on the {len(on)} event-reaction stock-days: actual avg range {on.target_range.mean():.2f}% vs {r.target_range.mean():.2f}% overall; prod forecast {on['prod'].mean():.2f}%, +EV forecast {on['+EV'].mean():.2f}%")
    sys.exit(0)
variants = {"prod": PROD, "+FO": PROD + FO, "+SEC": PROD + SEC, "+OVR": PROD + OVR, "+FLOW": PROD + FLOW, "+EV": PROD + EV, "+ALL": PROD + FO + SEC + OVR + FLOW + EV}
days = sorted(d["tradingDate"].unique()); test_days = days[-N_TEST:]
out = []
for i in range(N_TEST // 40):
    fd = test_days[i * 40:(i + 1) * 40]
    tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]; te = d[d["tradingDate"].isin(fd)].copy()
    for name, feats in variants.items():
        te[name] = LGBMRegressor(**rf._PARAMS).fit(tr[feats], tr["target_range"]).predict(te[feats])
    # production inputs but the NSE results flag instead of Yahoo's
    tr2 = tr.assign(results_next=tr["results_next_nse"]); te2 = te.assign(results_next=te["results_next_nse"])
    te["NSE-RESULTS"] = LGBMRegressor(**rf._PARAMS).fit(tr2[PROD], tr2["target_range"]).predict(te2[PROD])
    out.append(te); print("fold", i + 1, flush=True)
r = pd.concat(out, ignore_index=True)
base = (r.target_range - r.target_range.mean()).abs().mean()
ref = (r.target_range - r["prod"]).abs().groupby(r.tradingDate).mean()
print(f"\n=== next-day range, {len(r):,} out-of-sample stock-days over {r.tradingDate.nunique()} days (same rows for every variant) ===")
print(f"{'variant':12s} {'MAE':>7s} {'vs prod':>8s} {'t vs prod':>10s}   2024 / 2025 / 2026 MAE")
for name in list(variants) + ["NSE-RESULTS"]:
    e = (r.target_range - r[name]).abs().groupby(r.tradingDate).mean()
    t = "" if name == "prod" else f"{(ref - e).mean() / ((ref - e).std(ddof=1) / np.sqrt(len(e))):+.2f}"
    mae = (r.target_range - r[name]).abs().mean(); mae0 = (r.target_range - r["prod"]).abs().mean()
    yrs = " / ".join(f"{(gy.target_range - gy[name]).abs().mean():.4f}" for _, gy in r.groupby(r.tradingDate.dt.year))
    print(f"{name:12s} {mae:7.4f} {100 * (1 - mae / mae0):+7.2f}% {t:>10s}   {yrs}")
# results days specifically (where the NSE flag could matter)
rd = r[(r.results_next == 1) | (r.results_next_nse == 1)]
print(f"\nrows where either source says results are next day: {len(rd)} ({100 * len(rd) / len(r):.1f}%); both agree on {int(((rd.results_next == 1) & (rd.results_next_nse == 1)).sum())}")
for name in ("prod", "NSE-RESULTS"):
    print(f"   {name:12s} MAE on those rows {(rd.target_range - rd[name]).abs().mean():.4f}")
