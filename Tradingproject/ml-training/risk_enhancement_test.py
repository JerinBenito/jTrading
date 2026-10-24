"""Can three literature-backed ideas improve the next-day RISK model (risk_forecast.py) on THIS data? Walk-forward, last N_TEST
days in 40-day folds, day = unit for significance, same hyperparameters; target = next trading day's high-low range (%).

  base   : production features (range lags, |returns|, volume ratio, gap, market-wide range, results flag, weekday)
  +VIX   : India VIX level, 1-day change and level vs its 20-day mean (NSE implied vol; Yahoo ^INDIAVIX history)
  +RV    : realized-variance measures from 1-MINUTE candles - realized vol (5-min sampled), bipower jump share, negative
           semivariance share, overnight-gap variance, early (first 30 min) and late (last 60 min) shares of the day's range
  +both  : VIX + RV
Plus ACI-style online calibration of the 90th-percentile cushion (Gibbs & Candes 2021 idea): a multiplicative scale on q90,
updated daily from that day's miss rate, versus the static q90.
Usage: python risk_enhancement_test.py <earnings_dates.csv>
"""
import json, os, sys, urllib.request as u
import numpy as np, pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import risk_forecast as rf
import minute_store as ms
import multiday_volume_ablation as mv

N_TEST = int(os.environ.get("N_TEST", 600))
EARN = sys.argv[1]
PARAMS = rf._PARAMS

# ---------- base table (identical to risk_model_validation.py)
raw = mv.build_frame(); raw["tradingDate"] = pd.to_datetime(raw["tradingDate"])
raw = raw[~raw["instrument"].isin(["NIFTY", "BANKNIFTY"])]
for c in ("open", "high", "low", "close", "volumeRatio20d", "gapFromPrevClosePct"):
    raw[c] = pd.to_numeric(raw[c], errors="coerce")
raw["range"] = (raw["high"] - raw["low"]) / raw["open"] * 100; raw["gap"] = raw["gapFromPrevClosePct"]
ev = pd.read_csv(EARN); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]); ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
results = {s: list(x["date"].dt.date) for s, x in ev.groupby("instrument")}
d = rf.prepare({s: g[["tradingDate", "open", "close", "range", "volumeRatio20d", "gap"]] for s, g in raw.groupby("instrument")}, results)

# ---------- India VIX (implied volatility)
req = u.Request("https://query1.finance.yahoo.com/v8/finance/chart/%5EINDIAVIX?range=10y&interval=1d", headers={"User-Agent": "Mozilla/5.0"})
v = json.loads(u.urlopen(req, timeout=60).read())["chart"]["result"][0]
vix = pd.Series(v["indicators"]["quote"][0]["close"], index=pd.to_datetime(v["timestamp"], unit="s").tz_localize(None).normalize()).dropna()
vix = vix[~vix.index.duplicated()]
vf = pd.DataFrame({"vix": vix, "vix_chg": vix.pct_change() * 100, "vix_rel": vix / vix.rolling(20, min_periods=10).mean()})
d = d.merge(vf, left_on="tradingDate", right_index=True, how="left")
print(f"India VIX history {vix.index.min().date()}..{vix.index.max().date()}, {len(vix)} days", flush=True)

# ---------- realized-variance features from the minute store
rows = []
for sym in [s for s in ms.symbols() if s not in ("NIFTY", "BANKNIFTY")]:
    m = ms.load_minute(sym); prev_close = None
    for day, g in m.groupby("day"):
        if len(g) != 375:
            continue
        o, h, l, c = (g[k].to_numpy(float) for k in ("open", "high", "low", "close"))
        px = np.log(c[4::5])                                   # 5-minute sampled closes
        r = np.diff(px, prepend=np.log(o[0]))
        rv = np.sum(r ** 2); bv = (np.pi / 2) * np.sum(np.abs(r[1:]) * np.abs(r[:-1]))
        full = h.max() - l.min()
        rows.append(dict(instrument=sym, tradingDate=pd.Timestamp(day), rv=np.sqrt(rv) * 100,
                         jump=max(rv - bv, 0) / rv if rv > 0 else 0, neg=np.sum(r[r < 0] ** 2) / rv if rv > 0 else 0.5,
                         gap2=((o[0] / prev_close - 1) * 100) ** 2 if prev_close else np.nan,
                         early=(h[:30].max() - l[:30].min()) / full if full > 0 else np.nan,
                         late=(h[-60:].max() - l[-60:].min()) / full if full > 0 else np.nan))
        prev_close = c[-1]
rv = pd.DataFrame(rows)
rv["rv5"] = rv.groupby("instrument")["rv"].transform(lambda s: s.rolling(5, min_periods=5).mean())
rv["rv20"] = rv.groupby("instrument")["rv"].transform(lambda s: s.rolling(20, min_periods=20).mean())
d = d.merge(rv, on=["instrument", "tradingDate"], how="left")
print(f"realized-variance rows {len(rv):,}", flush=True)

VIXF = ["vix", "vix_chg", "vix_rel"]
RVF = ["rv", "rv5", "rv20", "jump", "neg", "gap2", "early", "late"]
variants = {"base": rf.FEATS, "+VIX": rf.FEATS + VIXF, "+RV": rf.FEATS + RVF, "+both": rf.FEATS + VIXF + RVF}
d = d.dropna(subset=rf.FEATS + ["target_range", "target_absret", "rv20", "vix_rel"]).reset_index(drop=True)
days = sorted(d["tradingDate"].unique()); test_days = days[-N_TEST:]
out = []
for i in range(N_TEST // 40):
    fd = test_days[i * 40:(i + 1) * 40]
    tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]; te = d[d["tradingDate"].isin(fd)].copy()
    for name, feats in variants.items():
        te[name] = LGBMRegressor(**PARAMS).fit(tr[feats], tr["target_range"]).predict(te[feats])
        if name in ("base", "+both"):
            te[name + "_q90"] = LGBMRegressor(objective="quantile", alpha=0.9, **PARAMS).fit(tr[feats], tr["target_range"]).predict(te[feats])
    out.append(te); print(f"fold {i + 1}", flush=True)
r = pd.concat(out, ignore_index=True)
base_mae = (r.target_range - r.target_range.mean()).abs().mean()
print(f"\n=== next-day range, {len(r):,} out-of-sample stock-days over {r.tradingDate.nunique()} days ===")
print(f"{'variant':8s} {'MAE':>7s} {'vs avg':>8s} {'Spearman':>9s} {'paired t vs base':>17s}")
ref = (r.target_range - r["base"]).abs().groupby(r.tradingDate).mean()
for name in variants:
    mae = (r.target_range - r[name]).abs().mean()
    t = ""
    if name != "base":
        diff = ref - (r.target_range - r[name]).abs().groupby(r.tradingDate).mean()
        t = f"{diff.mean() / (diff.std(ddof=1) / np.sqrt(len(diff))):+.2f} (better on {100 * (diff > 0).mean():.0f}% of days)"
    print(f"{name:8s} {mae:7.4f} {100 * (1 - mae / base_mae):7.2f}% {stats.spearmanr(r[name], r.target_range)[0]:+9.4f} {t:>17s}")
for yr, g in r.groupby(r.tradingDate.dt.year):
    print(f"   {yr}: base MAE {(g.target_range - g['base']).abs().mean():.4f}  +VIX {(g.target_range - g['+VIX']).abs().mean():.4f}  +RV {(g.target_range - g['+RV']).abs().mean():.4f}  +both {(g.target_range - g['+both']).abs().mean():.4f}")

# ---------- ACI-style online calibration of the q90 cushion
print("\n=== q90 cushion: static vs online (ACI-style) scale, using the +both model ===")
q = r.sort_values("tradingDate")
scale, eta, target_miss = 1.0, 0.15, 0.10
cov_static, cov_aci, wid_static, wid_aci, by_year = [], [], [], [], []
for day, g in q.groupby("tradingDate"):
    q90 = g["+both_q90"].to_numpy(); y = g.target_range.to_numpy()
    miss_static = (y > q90).mean(); miss_aci = (y > q90 * scale).mean()
    cov_static.append(1 - miss_static); cov_aci.append(1 - miss_aci)
    wid_static.append(q90.mean()); wid_aci.append((q90 * scale).mean())
    by_year.append((day.year, 1 - miss_static, 1 - miss_aci))
    scale *= np.exp(eta * (miss_aci - target_miss))          # too many misses -> widen, too few -> tighten
    scale = float(np.clip(scale, 0.7, 1.6))
print(f"static: coverage {100 * np.mean(cov_static):.1f}%, mean q90 {np.mean(wid_static):.2f}%, day-level coverage std {100 * np.std(cov_static):.1f} pts, days under 80%: {sum(c < 0.8 for c in cov_static)}")
print(f"ACI   : coverage {100 * np.mean(cov_aci):.1f}%, mean q90 {np.mean(wid_aci):.2f}%, day-level coverage std {100 * np.std(cov_aci):.1f} pts, days under 80%: {sum(c < 0.8 for c in cov_aci)}")
yy = pd.DataFrame(by_year, columns=["year", "static", "aci"]).groupby("year").mean() * 100
print(yy.round(1).to_string())
# rolling 60-day coverage worst case
rs = pd.Series(cov_static).rolling(60).mean().dropna() * 100; ra = pd.Series(cov_aci).rolling(60).mean().dropna() * 100
print(f"worst rolling-60-day coverage: static {rs.min():.1f}% vs ACI {ra.min():.1f}% (best {rs.max():.1f}% vs {ra.max():.1f}%)")
