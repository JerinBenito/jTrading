"""Walk-forward check of the PRODUCTION swing model (swing_model.py) built from the hourly export, to confirm it reproduces the
'hourly' variant of intraday_risk_test.py (rank corr ~0.64, q90 ~90.9%) and to test the live-row path against the training path.
Usage: python swing_walkforward.py <earnings_dates.csv>"""
import json, os, sys, time
import numpy as np, pandas as pd
from scipy import stats

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import swing_model as sm
import run_daily_ai_prediction as m

N_TEST = int(os.environ.get("N_TEST", 600))
ev = pd.read_csv(sys.argv[1]); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]).dt.date
results = {s: sorted((d, None) for d in x["date"]) for s, x in ev.groupby("instrument")}
t0 = time.time()
rows = m.get_json("/api/ml/intraday-features/all", timeout=900)
df = pd.DataFrame(rows)
print(f"{len(df):,} export rows in {time.time() - t0:.0f}s; has swing fields: {'remainingSwingPct' in df.columns}", flush=True)
df = m.add_results_offset(df, results)
df, dropped = m.drop_corporate_action_windows(df, m.corporate_action_days_intraday(df))
d = sm.training_frame(df)
print(f"{len(d):,} usable training rows", flush=True)
days = sorted(d["tradingDate"].unique()); test_days = days[-N_TEST:]
out = []
for i in range(N_TEST // 40):
    fd = test_days[i * 40:(i + 1) * 40]
    tr = d[d["tradingDate"] < fd[0]]; te = d[d["tradingDate"].isin(fd) & ~d["instrument"].isin(sm.STOCK_EXCLUDE)].copy()
    models = sm.fit(tr); te["mean"], te["q90"] = sm.predict(models, te); out.append(te)
    print(f"fold {i + 1}", flush=True)
r = pd.concat(out).dropna(subset=["target"])
base = (r.target - r.target.mean()).abs().mean()
mae = (r.target - r["mean"]).abs().mean()
print(f"\n{len(r):,} out-of-sample rows over {r.tradingDate.nunique()} days | MAE {mae:.3f} ({100 * (1 - mae / base):.1f}% better than average) | Spearman {stats.spearmanr(r['mean'], r.target)[0]:+.3f}")
print(f"90% cushion holds {100 * (r.target <= r.q90).mean():.1f}% | mean forecast {r['mean'].mean():.2f}% vs actual {r.target.mean():.2f}% | mean q90 {r.q90.mean():.2f}%")
for k, g in r.groupby("k"):
    print(f"  hour {int(k)}: forecast {g['mean'].mean():.2f}% actual {g.target.mean():.2f}% q90 holds {100 * (g.target <= g.q90).mean():.1f}% rho {stats.spearmanr(g['mean'], g.target)[0]:+.3f}")
# live path vs training path on the last completed day: feature equality
last_day = days[-1]
hist = sm.history_for_live(df[df["tradingDate"] < last_day])
live = {}
for sym, g in df[(df["tradingDate"] == last_day) & (df["hoursSinceOpen"] == 2)].groupby("instrument"):
    row = g.iloc[0].to_dict(); live[sym] = {k: row[k] for k in ("hoursSinceOpen", "returnSoFarPct", "volatilitySoFarPct", "lastCandleRangePct", "volumeSoFarRatio", "currentPrice", "tradingDate")}
lr = sm.live_rows(live, hist)
tr = d[(d.tradingDate == last_day) & (d.k == 2)].set_index("instrument")
lr = lr.set_index("instrument")
common = [s for s in lr.index if s in tr.index and s not in sm.STOCK_EXCLUDE]
diff = {f: float(np.nanmax(np.abs(lr.loc[common, f].astype(float) - tr.loc[common, f].astype(float)))) for f in ("ret", "rng", "last_rng", "prev_rng", "r5", "r20", "gap", "mkt_rng")}
print(f"\nlive-path vs training-path features on {last_day} hour 2 ({len(common)} stocks), max abs difference:", {k: round(v, 6) for k, v in diff.items()})
