"""How predictable is NEXT-DAY RANGE (volatility) compared with next-day direction, and how much better than simple
baselines can a model do?  Every test so far says direction is ~unpredictable; size looks predictable (results days
2x, volume clustering). This quantifies it.

Target: next trading day's high-low range as % of open (the day's full swing), per stock.
Baselines: (1) ATR14 as % of close (what most people use); (2) 'HAR' average of the last 1/5/20 day ranges
(OLS-fitted on the training window, the standard realised-volatility benchmark).
Model: LightGBM on range lags, |returns|, volume ratio, gap, 5/20d returns, market-wide range (cross-sectional mean of
the same day), day of week, and a results-day flag from the vendor calendar (next day is the results date or the day
after). Walk-forward (expanding, last 240 days in 6 folds); day is the unit for significance.
Reports MAE, Spearman, and results-day vs normal-day performance, plus the same for NEXT-DAY DIRECTION for contrast.
"""
import os, sys
import numpy as np, pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats

sys.path.insert(0, os.path.dirname(__file__))
import multiday_volume_ablation as mv

EARN = sys.argv[1] if len(sys.argv) > 1 else None
df = mv.build_frame()
df["tradingDate"] = pd.to_datetime(df["tradingDate"])
df = df[~df["instrument"].isin(["NIFTY", "BANKNIFTY"])].sort_values(["instrument", "tradingDate"]).reset_index(drop=True)
for c in ("open", "high", "low", "close", "volume", "dailyReturnPct", "gapFromPrevClosePct", "atr14", "volumeRatio20d", "return5dPct", "return20dPct"):
    df[c] = pd.to_numeric(df[c], errors="coerce")
df["range"] = (df["high"] - df["low"]) / df["open"] * 100
g = df.groupby("instrument")
df["r1"] = df["range"]
df["r5"] = g["range"].transform(lambda s: s.rolling(5, min_periods=5).mean())
df["r20"] = g["range"].transform(lambda s: s.rolling(20, min_periods=20).mean())
df["absret"] = df["dailyReturnPct"].abs()
df["absret5"] = g["absret"].transform(lambda s: s.rolling(5, min_periods=5).mean())
df["atrpct"] = df["atr14"] / df["close"] * 100
df["mkt_range"] = df.groupby("tradingDate")["range"].transform("mean")
df["mkt_range5"] = df.groupby("tradingDate")["mkt_range"].transform("first")
mk = df.groupby("tradingDate")["range"].mean().rolling(5, min_periods=5).mean()
df["mkt_range5"] = df["tradingDate"].map(mk)
df["dow_next"] = (df["tradingDate"].dt.dayofweek + 1) % 5
df["target"] = g["range"].shift(-1)                                   # next day's range
df["target_ret"] = g["close"].transform(lambda s: s.shift(-1) / s - 1) * 100
df["target_up"] = (df["target_ret"] > 0).astype(float)

# results flag: does the NEXT trading day fall on / right after the vendor results date?
df["results_next"] = 0.0
if EARN:
    ev = pd.read_csv(EARN); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]); ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
    ed = {s: x["date"].sort_values().values.astype("datetime64[D]") for s, x in ev.groupby("instrument")}
    nxt = df.groupby("instrument")["tradingDate"].shift(-1)
    flag = []
    for s, d1 in zip(df["instrument"], nxt):
        e = ed.get(s)
        flag.append(0.0 if e is None or pd.isna(d1) else float(np.any((e <= np.datetime64(d1, "D")) & (np.datetime64(d1, "D") - e <= np.timedelta64(1, "D")))))
    df["results_next"] = flag
FEATS = ["r1", "r5", "r20", "absret", "absret5", "atrpct", "volumeRatio20d", "gapFromPrevClosePct", "return5dPct", "return20dPct",
         "mkt_range", "mkt_range5", "dow_next", "results_next"]
d = df.dropna(subset=FEATS + ["target", "target_ret"]).reset_index(drop=True)
days = sorted(d["tradingDate"].unique()); test_days = days[-240:]
folds = [test_days[i * 40:(i + 1) * 40] for i in range(6)]
rows = []
for fd in folds:
    tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]
    te = d[d["tradingDate"].isin(fd)].copy()
    # HAR baseline: OLS of target on r1, r5, r20
    X = np.column_stack([np.ones(len(tr)), tr["r1"], tr["r5"], tr["r20"]]); b = np.linalg.lstsq(X, tr["target"], rcond=None)[0]
    te["har"] = np.column_stack([np.ones(len(te)), te["r1"], te["r5"], te["r20"]]) @ b
    te["atr"] = te["atrpct"] * 1.0
    # ATR is a different scale than range: rescale by the training mean ratio so the comparison is about ranking/shape, not units
    te["atr"] = te["atrpct"] * (tr["target"].mean() / tr["atrpct"].mean())
    m = LGBMRegressor(n_estimators=300, max_depth=4, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
    m.fit(tr[FEATS], tr["target"]); te["lgbm"] = m.predict(te[FEATS])
    # direction model for contrast
    md = LGBMRegressor(n_estimators=300, max_depth=4, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
    md.fit(tr[FEATS], tr["target_ret"]); te["dir_pred"] = md.predict(te[FEATS])
    rows.append(te)
r = pd.concat(rows, ignore_index=True)
print(f"{len(r)} stock-days, {r.tradingDate.nunique()} test days; mean next-day range {r.target.mean():.2f}%")


def report(x, label):
    base_mae = (x.target - x.target.mean()).abs().mean()
    print(f"\n--- {label} (n={len(x)}) ---")
    print(f"  {'forecast':12s} {'MAE %':>7s} {'vs always-average':>18s} {'Spearman':>9s}")
    for k in ("atr", "har", "lgbm"):
        mae = (x.target - x[k]).abs().mean()
        print(f"  {k:12s} {mae:7.3f} {100 * (1 - mae / base_mae):17.1f}% {stats.spearmanr(x[k], x.target)[0]:+9.3f}")


report(r, "ALL days")
report(r[r.results_next == 1], "next day = results reaction day")
report(r[r.results_next == 0], "ordinary days")
# paired by day: lgbm vs har
dd = r.assign(e_h=(r.target - r.har).abs(), e_l=(r.target - r.lgbm).abs()).groupby("tradingDate")[["e_h", "e_l"]].mean()
diff = dd.e_h - dd.e_l; t = diff.mean() / (diff.std(ddof=1) / np.sqrt(len(diff)))
print(f"\nLGBM vs HAR, paired over {len(diff)} days: LGBM better on {100 * (diff > 0).mean():.0f}% of days, mean gain {diff.mean():.4f} pts, t={t:+.2f}")
# the contrast: direction
ic = r.groupby("tradingDate").apply(lambda g: stats.spearmanr(g.dir_pred, g.target_ret)[0] if len(g) > 20 else np.nan).dropna()
hit = ((np.sign(r.dir_pred) == np.sign(r.target_ret))).mean() * 100
print(f"CONTRAST - next-day DIRECTION with the same inputs: hit rate {hit:.1f}%, mean daily cross-sectional rank IC {ic.mean():+.3f} (t={ic.mean() / (ic.std() / np.sqrt(len(ic))):+.2f})")
# interval sharpness at equal coverage: 'predicted range x k' covering 90% of next-day ranges
for k_, lab in (("har", "HAR"), ("lgbm", "LGBM")):
    ratio = r.target / r[k_].clip(lower=0.1)
    q = np.quantile(ratio, 0.9)
    print(f"to cover 90% of next-day ranges, {lab} needs a cushion of x{q:.2f}; typical cushion width = {q * r[k_].mean():.2f}% of price")
