"""Is the risk model calibrated AFTER wide-swing days (the case that matters for a risk tool)? Out-of-sample (600 days,
15 expanding folds), bins of the market-wide range today vs mean predicted and mean actual NEXT-day range, plus the same
for the 90th percentile coverage. Usage: python risk_conditional_check.py <earnings_dates.csv>"""
import os, sys
import numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import risk_forecast as rf
import multiday_volume_ablation as mv
raw = mv.build_frame(); raw["tradingDate"] = pd.to_datetime(raw["tradingDate"])
raw = raw[~raw["instrument"].isin(["NIFTY", "BANKNIFTY"])]
for c in ("open", "high", "low", "close", "volumeRatio20d", "gapFromPrevClosePct"): raw[c] = pd.to_numeric(raw[c], errors="coerce")
raw["range"] = (raw["high"] - raw["low"]) / raw["open"] * 100; raw["gap"] = raw["gapFromPrevClosePct"]
ev = pd.read_csv(sys.argv[1]); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]); ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
results = {s: list(x["date"].dt.date) for s, x in ev.groupby("instrument")}
d = rf.prepare({s: g[["tradingDate", "open", "close", "range", "volumeRatio20d", "gap"]] for s, g in raw.groupby("instrument")}, results)
d = d.dropna(subset=rf.FEATS + ["target_range", "target_absret"]).reset_index(drop=True)
days = sorted(d["tradingDate"].unique()); test_days = days[-600:]
out = []
for i in range(15):
    fd = test_days[i * 40:(i + 1) * 40]
    tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]; te = d[d["tradingDate"].isin(fd)].copy()
    m = rf.fit(tr); te["mean"], te["q90"], te["big"] = rf.predict(m, te)
    # naive persistence benchmark: tomorrow = blend of today and 20d average, fitted on training window
    X = np.column_stack([np.ones(len(tr)), tr["r1"], tr["r20"]]); b = np.linalg.lstsq(X, tr["target_range"], rcond=None)[0]
    te["har2"] = np.column_stack([np.ones(len(te)), te["r1"], te["r20"]]) @ b
    out.append(te)
r = pd.concat(out)
dm = r.groupby("tradingDate").agg(mkt=("mkt_range", "first"), pred=("mean", "mean"), har=("har2", "mean"), act=("target_range", "mean"), q90=("q90", "mean"))
dm["bin"] = pd.cut(dm.mkt, [0, 1.8, 2.2, 2.6, 3.0, 10])
print("market-wide range TODAY (bin) -> mean predicted vs actual NEXT-day basket range (per-day averages):")
print(dm.groupby("bin", observed=True).agg(days=("pred", "size"), predicted=("pred", "mean"), simple_blend=("har", "mean"), actual=("act", "mean")).round(2).to_string())
rr = r.assign(wide=r.mkt_range >= 3.0)
for lab, g in rr.groupby("wide"):
    print(f"  {'wide days (mkt range >= 3.0)' if lab else 'other days':30s} n={len(g):6d} q90 coverage {100 * (g.target_range <= g.q90).mean():.1f}%  mean predicted {g['mean'].mean():.2f} vs actual {g.target_range.mean():.2f}")
