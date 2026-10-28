"""Extra features from NSE's daily F&O bhavcopy (already downloaded by fo_bhavcopy_fetch.py), beyond the one implied-vol number:
  pcr_oi     put open interest / call open interest (all strikes, expiries within 75 days)
  fut_oi_chg % change in total futures open interest vs the previous trading day
  basis      near-month futures premium over spot, annualised %
  skew       implied vol of the ~5% out-of-the-money put minus the ~5% out-of-the-money call (nearest expiry >= 12 days)
  term       implied vol of the next expiry minus the nearest (>= 5 days): the shape of the vol curve
Output: data/fo/extras_daily.csv (date, sym, ...).  Usage: python fo_extras.py
"""
import glob, json, os, urllib.request as u
import numpy as np, pandas as pd

import fo_iv

B = "https://jerintradingsignal.duckdns.org"
HERE = os.path.dirname(os.path.abspath(__file__))
R = fo_iv.R
basket = [r["symbol"] for r in json.loads(u.urlopen(B + "/api/monitor/basket", timeout=60).read()) if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
spot = {}
for s in basket:
    rows = json.loads(u.urlopen(f"{B}/api/ml/features/{s}", timeout=120).read())
    spot[s] = pd.Series({r["tradingDate"]: float(r["close"]) for r in rows})
ALIAS = {"TATAMOTORS": "TMPV"}
out = []
for f in sorted(glob.glob(os.path.join(HERE, "data", "fo", "????-??.csv.gz"))):
    raw = pd.read_csv(f, low_memory=False)
    raw["sym"] = raw["sym"].replace(ALIAS)
    raw = raw[raw["sym"].isin(basket)].copy()
    raw["S"] = [spot[s].get(dt, np.nan) for s, dt in zip(raw["sym"], raw["date"])]
    raw = raw.dropna(subset=["S"])
    if raw.empty:
        continue                                   # before our own price history starts (early 2022)
    raw["T"] = (pd.to_datetime(raw["expiry"]) - pd.to_datetime(raw["date"])).dt.days.clip(lower=1) / 365.0
    # --- open-interest / futures pieces (no IV inversion needed)
    opt = raw[raw["tp"] == "OPT"]
    pc = opt.assign(is_put=opt["opt"] == "PE").groupby(["date", "sym", "is_put"])["oi"].sum().unstack("is_put")
    pcr = (pc.get(True) / pc.get(False)).rename("pcr_oi")
    fut = raw[raw["tp"] == "FUT"]
    fut_oi = fut.groupby(["date", "sym"])["oi"].sum().rename("fut_oi")
    near = fut.sort_values("T").groupby(["date", "sym"]).head(1).set_index(["date", "sym"])
    basis = (((near["settle"] / near["S"]) - 1) / near["T"] * 100).rename("basis")
    # --- implied-vol pieces
    o = opt.copy()
    o["F"] = o["S"] * np.exp(R * o["T"])
    o = o[(np.abs(np.log(o["strike"] / o["F"])) <= 0.12) & (o["settle"] > 0.5) & (o["oi"] > 0)]
    o["is_call"] = o["opt"] == "CE"
    o = o[(o["is_call"] & (o["strike"] >= o["F"])) | (~o["is_call"] & (o["strike"] < o["F"]))]
    o["iv"] = fo_iv.implied_vol(o["settle"].to_numpy(float), o["S"].to_numpy(float), o["strike"].to_numpy(float), o["T"].to_numpy(float), o["is_call"].to_numpy())
    o = o.dropna(subset=["iv"])
    o["dist"] = np.abs(np.log(o["strike"] / o["F"]))
    atm = o.sort_values("dist").groupby(["date", "sym", "expiry", "is_call"]).head(1).groupby(["date", "sym", "expiry"]).agg(iv=("iv", "mean"), n=("iv", "size"), T=("T", "first")).reset_index()
    atm = atm[atm["n"] == 2]
    rows = {}
    for (d, s), g in atm.groupby(["date", "sym"]):
        g = g[(g["T"] >= 5 / 365) & (g["T"] <= 70 / 365)].sort_values("T")
        if len(g) >= 2:
            rows[(d, s)] = {"term": (g.iloc[1]["iv"] - g.iloc[0]["iv"]) * 100}
    for (d, s), g in o[o["T"] >= 12 / 365].groupby(["date", "sym"]):
        e = g[g["T"] == g["T"].min()]
        puts = e[~e["is_call"]]; calls = e[e["is_call"]]
        if puts.empty or calls.empty:
            continue
        F = e["F"].iloc[0]
        p = puts.iloc[(np.abs(np.log(puts["strike"] / (0.95 * F)))).argmin()]; c = calls.iloc[(np.abs(np.log(calls["strike"] / (1.05 * F)))).argmin()]
        if abs(np.log(p["strike"] / (0.95 * F))) <= 0.03 and abs(np.log(c["strike"] / (1.05 * F))) <= 0.03:
            rows.setdefault((d, s), {})["skew"] = (p["iv"] - c["iv"]) * 100
    iv_part = pd.DataFrame.from_dict(rows, orient="index") if rows else pd.DataFrame(columns=["term", "skew"])
    if len(iv_part):
        iv_part.index = pd.MultiIndex.from_tuples(iv_part.index, names=["date", "sym"])
    out.append(pd.concat([pcr, fut_oi, basis, iv_part], axis=1))
    print(os.path.basename(f), len(out[-1]), flush=True)
res = pd.concat(out).reset_index().sort_values(["sym", "date"])
res["fut_oi_chg"] = res.groupby("sym")["fut_oi"].pct_change() * 100
res.drop(columns=["fut_oi"]).to_csv(os.path.join(HERE, "data", "fo", "extras_daily.csv"), index=False)
print(res.describe().round(2).to_string())
