"""Walk-forward validation of risk_forecast.py: last 240 days in 6 expanding folds (purge 2 days), day = unit.
Checks (1) expected-range accuracy vs ATR-style and HAR baselines, (2) whether the 90th-percentile range really
holds ~90% of next-day ranges, (3) whether the 'big move' probability is calibrated (predicted vs observed by decile)
and discriminates (AUC). Writes risk_validation.json for the app to show an honest reliability note.
Usage: python risk_model_validation.py <earnings_dates.csv>
"""
import json, os, sys
import numpy as np, pandas as pd
from scipy import stats

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import risk_forecast as rf
import multiday_volume_ablation as mv

EARN = sys.argv[1]
raw = mv.build_frame()
raw["tradingDate"] = pd.to_datetime(raw["tradingDate"])
raw = raw[~raw["instrument"].isin(["NIFTY", "BANKNIFTY"])]
for c in ("open", "high", "low", "close", "volumeRatio20d", "gapFromPrevClosePct"):
    raw[c] = pd.to_numeric(raw[c], errors="coerce")
raw["range"] = (raw["high"] - raw["low"]) / raw["open"] * 100
raw["gap"] = raw["gapFromPrevClosePct"]
ev = pd.read_csv(EARN); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]); ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
results = {s: list(x["date"].dt.date) for s, x in ev.groupby("instrument")}
frames = {s: g[["tradingDate", "open", "close", "range", "volumeRatio20d", "gap"]] for s, g in raw.groupby("instrument")}
d = rf.prepare(frames, results)
d = d.dropna(subset=rf.FEATS + ["target_range", "target_absret"]).reset_index(drop=True)
days = sorted(d["tradingDate"].unique()); test_days = days[-240:]
folds = [test_days[i * 40:(i + 1) * 40] for i in range(6)]
out = []
for fd in folds:
    tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]
    te = d[d["tradingDate"].isin(fd)].copy()
    models = rf.fit(tr)
    te["mean"], te["q90"], te["big"] = rf.predict(models, te)
    X = np.column_stack([np.ones(len(tr)), tr["r1"], tr["r5"], tr["r20"]]); b = np.linalg.lstsq(X, tr["target_range"], rcond=None)[0]
    te["har"] = np.column_stack([np.ones(len(te)), te["r1"], te["r5"], te["r20"]]) @ b
    te["atrlike"] = te["r14"] * (tr["target_range"].mean() / tr["r14"].mean())
    out.append(te)
r = pd.concat(out, ignore_index=True)
print(f"{len(r)} stock-days over {r.tradingDate.nunique()} test days; mean next-day range {r.target_range.mean():.2f}%, big-move base rate {100 * (r.target_absret >= rf.BIG_MOVE_PCT).mean():.1f}%")
base = (r.target_range - r.target_range.mean()).abs().mean()
res = {}
print(f"\n{'forecast':10s} {'MAE':>7s} {'vs avg':>8s} {'Spearman':>9s}")
for k in ("atrlike", "har", "mean"):
    mae = (r.target_range - r[k]).abs().mean(); sp = stats.spearmanr(r[k], r.target_range)[0]
    res[k] = {"mae": round(mae, 3), "vsAveragePct": round(100 * (1 - mae / base), 1), "spearman": round(sp, 3)}
    print(f"{k:10s} {mae:7.3f} {100 * (1 - mae / base):7.1f}% {sp:+9.3f}")
cov = (r.target_range <= r.q90).mean() * 100
print(f"\n90th-percentile range holds {cov:.1f}% of next-day ranges (target 90%); typical q90 = {r.q90.mean():.2f}% vs expected {r['mean'].mean():.2f}%")
y = (r.target_absret >= rf.BIG_MOVE_PCT).astype(int)
from sklearn.metrics import roc_auc_score
auc = roc_auc_score(y, r.big)
print(f"big-move (>= {rf.BIG_MOVE_PCT}% close-to-close) classifier AUC {auc:.3f} (0.5 = no skill)")
r["bin"] = pd.qcut(r.big, 10, duplicates="drop")
cal = r.groupby("bin", observed=True).agg(pred=("big", "mean"), obs=("target_absret", lambda s: (s >= rf.BIG_MOVE_PCT).mean()), n=("big", "size"))
print("calibration by decile (predicted vs observed %):")
for _, row in cal.iterrows(): print(f"   pred {100 * row.pred:5.1f}%  observed {100 * row.obs:5.1f}%  (n={int(row.n)})")
top = r[r.big >= r.big.quantile(0.9)]
print(f"top-10% flagged days: observed big-move rate {100 * (top.target_absret >= rf.BIG_MOVE_PCT).mean():.1f}% vs base {100 * y.mean():.1f}%")
# paired by day vs HAR
dd = r.assign(a=(r.target_range - r.har).abs(), b=(r.target_range - r["mean"]).abs()).groupby("tradingDate")[["a", "b"]].mean()
diff = dd.a - dd.b; t = diff.mean() / (diff.std(ddof=1) / np.sqrt(len(diff)))
print(f"expected-range model vs HAR, paired over {len(diff)} days: better on {100 * (diff > 0).mean():.0f}% of days, t={t:+.2f}")
json.dump({"builtOn": str(pd.Timestamp.today().date()), "testDays": int(r.tradingDate.nunique()), "stockDays": int(len(r)),
           "rangeForecast": res, "q90Coverage": round(cov, 1), "bigMoveThresholdPct": rf.BIG_MOVE_PCT, "bigMoveAuc": round(auc, 3),
           "bigMoveBaseRatePct": round(100 * y.mean(), 1), "bigMoveTopDecilePct": round(100 * (top.target_absret >= rf.BIG_MOVE_PCT).mean(), 1),
           "pairedTvsHar": round(float(t), 2)}, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "risk_validation.json"), "w"), indent=1)
print("\nwrote risk_validation.json")
