"""Does option-implied volatility (NSE F&O bhavcopy -> fo_iv.py) improve the next-day RISK model, on top of the realized-variance
measures from minute data? Walk-forward, last N_TEST days in 40-day folds, day = unit, identical hyperparameters, all variants
scored on the SAME rows (stock-days with an IV reading). Target: next trading day's high-low range (%).
  base | +RV (realized vol measures) | +IV (iv30, daily-equivalent IV, IV vs its 60-day mean, 1-day IV change, IV-minus-realized)
  | +RV+IV.   Usage: python iv_risk_test.py <earnings_dates.csv>
"""
import os, sys
import numpy as np, pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import risk_forecast as rf
import minute_store as ms
import multiday_volume_ablation as mv

HERE = os.path.dirname(os.path.abspath(__file__))
N_TEST = int(os.environ.get("N_TEST", 600))
raw = mv.build_frame(); raw["tradingDate"] = pd.to_datetime(raw["tradingDate"])
raw = raw[~raw["instrument"].isin(["NIFTY", "BANKNIFTY"])]
for c in ("open", "high", "low", "close", "volumeRatio20d", "gapFromPrevClosePct"):
    raw[c] = pd.to_numeric(raw[c], errors="coerce")
raw["range"] = (raw["high"] - raw["low"]) / raw["open"] * 100; raw["gap"] = raw["gapFromPrevClosePct"]
ev = pd.read_csv(sys.argv[1]); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]); ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
results = {s: list(x["date"].dt.date) for s, x in ev.groupby("instrument")}
d = rf.prepare({s: g[["tradingDate", "open", "close", "range", "volumeRatio20d", "gap"]] for s, g in raw.groupby("instrument")}, results)

# realized-variance measures (cached)
cache = os.path.join(HERE, "data", "rv_daily.csv.gz")
if os.path.exists(cache):
    rv = pd.read_csv(cache, parse_dates=["tradingDate"])
else:
    rows = []
    for sym in [s for s in ms.symbols() if s not in ("NIFTY", "BANKNIFTY")]:
        m = ms.load_minute(sym); prev_close = None
        for day, g in m.groupby("day"):
            if len(g) != 375:
                continue
            o, h, l, c = (g[k].to_numpy(float) for k in ("open", "high", "low", "close"))
            r = np.diff(np.log(c[4::5]), prepend=np.log(o[0]))
            rvv = np.sum(r ** 2); bv = (np.pi / 2) * np.sum(np.abs(r[1:]) * np.abs(r[:-1])); full = h.max() - l.min()
            rows.append(dict(instrument=sym, tradingDate=pd.Timestamp(day), rv=np.sqrt(rvv) * 100, jump=max(rvv - bv, 0) / rvv if rvv > 0 else 0,
                             neg=np.sum(r[r < 0] ** 2) / rvv if rvv > 0 else 0.5, gap2=((o[0] / prev_close - 1) * 100) ** 2 if prev_close else np.nan,
                             early=(h[:30].max() - l[:30].min()) / full if full > 0 else np.nan, late=(h[-60:].max() - l[-60:].min()) / full if full > 0 else np.nan))
            prev_close = c[-1]
    rv = pd.DataFrame(rows)
    rv["rv5"] = rv.groupby("instrument")["rv"].transform(lambda s: s.rolling(5, min_periods=5).mean())
    rv["rv20"] = rv.groupby("instrument")["rv"].transform(lambda s: s.rolling(20, min_periods=20).mean())
    rv.to_csv(cache, index=False, compression="gzip")
d = d.merge(rv, on=["instrument", "tradingDate"], how="left")

iv = pd.read_csv(os.path.join(HERE, "data", "fo", "iv_daily.csv"), parse_dates=["date"]).rename(columns={"sym": "instrument", "date": "tradingDate"})
iv["iv_d"] = iv["iv30"] / np.sqrt(252)
g = iv.sort_values(["instrument", "tradingDate"]).groupby("instrument")["iv30"]
iv["iv_rel"] = iv["iv30"] / g.transform(lambda s: s.rolling(60, min_periods=20).mean())
iv["iv_chg"] = g.transform(lambda s: s.pct_change() * 100)
d = d.merge(iv[["instrument", "tradingDate", "iv30", "iv_d", "iv_rel", "iv_chg"]], on=["instrument", "tradingDate"], how="left")
d["vrp"] = d["iv_d"] - d["rv20"]
RVF = ["rv", "rv5", "rv20", "jump", "neg", "gap2", "early", "late"]
IVF = ["iv30", "iv_d", "iv_rel", "iv_chg", "vrp"]
IV4 = ["iv30", "iv_d", "iv_rel", "iv_chg"]            # the production set: needs no minute data
variants = {"base": rf.FEATS, "+RV": rf.FEATS + RVF, "+IV": rf.FEATS + IVF, "+IV4": rf.FEATS + IV4, "+RV+IV": rf.FEATS + RVF + IVF}
d = d.dropna(subset=rf.FEATS + ["target_range", "target_absret", "rv20", "iv_d", "iv_rel", "iv_chg"]).reset_index(drop=True)
print(f"{len(d):,} stock-days with every input; {d.tradingDate.min().date()}..{d.tradingDate.max().date()}", flush=True)
days = sorted(d["tradingDate"].unique()); test_days = days[-N_TEST:]
out = []
for i in range(N_TEST // 40):
    fd = test_days[i * 40:(i + 1) * 40]
    tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]; te = d[d["tradingDate"].isin(fd)].copy()
    for name, feats in variants.items():
        te[name] = LGBMRegressor(**rf._PARAMS).fit(tr[feats], tr["target_range"]).predict(te[feats])
    out.append(te); print(f"fold {i + 1}", flush=True)
r = pd.concat(out, ignore_index=True)
base_mae = (r.target_range - r.target_range.mean()).abs().mean()
ref = (r.target_range - r["base"]).abs().groupby(r.tradingDate).mean()
ref_rv = (r.target_range - r["+RV"]).abs().groupby(r.tradingDate).mean()
print(f"\n=== next-day range, {len(r):,} out-of-sample stock-days over {r.tradingDate.nunique()} days (same rows for every variant) ===")
print(f"{'variant':8s} {'MAE':>7s} {'vs avg':>8s} {'Spearman':>9s} {'t vs base':>10s} {'t vs +RV':>9s}")
for name in variants:
    e = (r.target_range - r[name]).abs().groupby(r.tradingDate).mean()
    tb = "" if name == "base" else f"{(ref - e).mean() / ((ref - e).std(ddof=1) / np.sqrt(len(e))):+.2f}"
    tr_ = "" if name in ("base", "+RV") else f"{(ref_rv - e).mean() / ((ref_rv - e).std(ddof=1) / np.sqrt(len(e))):+.2f}"
    mae = (r.target_range - r[name]).abs().mean()
    print(f"{name:8s} {mae:7.4f} {100 * (1 - mae / base_mae):7.2f}% {stats.spearmanr(r[name], r.target_range)[0]:+9.4f} {tb:>10s} {tr_:>9s}")
for yr, gy in r.groupby(r.tradingDate.dt.year):
    print(f"   {yr}: " + "  ".join(f"{n} {(gy.target_range - gy[n]).abs().mean():.4f}" for n in variants))
print("\nhow much does IV know that realized vol does not? corr(iv_d, next-day range) =", round(r.iv_d.corr(r.target_range), 3), "| corr(rv20, next-day range) =", round(r.rv20.corr(r.target_range), 3),
      "| corr(r20 range, next-day range) =", round(r.r20.corr(r.target_range), 3))
