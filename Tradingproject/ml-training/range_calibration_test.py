"""Do the same-day 90% ranges (5%/95% quantile models) under-cover, and does split-conformal calibration fix it?

Live ledger (20 scored days): first-call ranges contain the close 86.9% of the time, last-call 78.2% (target 90%).
Conformalized quantile regression (CQR): hold out the most recent K trading days as a calibration set, fit the
production recipe on the days before them, score each calibration row E = max(lo - y, y - hi), take per-hour the
ceil((n+1)*0.9)/n empirical quantile q_h, and widen the FINAL model's range to [lo - q_h, hi + q_h] (q_h may be
negative = shrink). Walk-forward, same folds/harness as the other ablations; calibration days are always BEFORE the
fold being scored. Reports coverage, width and pinball (lo/hi only) uncalibrated vs calibrated, overall and by hour.
"""
import os, sys
import numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

K_CAL = int(os.environ.get("K_CAL", 60))
MODE = os.environ.get("MODE", "add")        # add: widen by q (pct points); mult: widen by q x (hi-lo)
df = va.build_frame()
kept, _ = va.m.select_intraday_features(df)
kept = [f for f in kept if f != "resultsDayOffset"]
stock = ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])
days = sorted(df["tradingDate"].unique()); test_days = days[-va.N_TEST_DAYS:]
block = len(test_days) // va.N_FOLDS
folds = [test_days[i * block:(i + 1) * block if i < va.N_FOLDS - 1 else len(test_days)] for i in range(va.N_FOLDS)]
day_idx = {d: i for i, d in enumerate(days)}


def fit(train, feats):
    med = train.groupby("hoursSinceOpen")["remainingDriftPct"].median()
    t = train.copy(); t["remainingDriftPct"] -= t["hoursSinceOpen"].map(med)         # production centring
    return t, feats


def predict(train, test, feats):
    t, _ = fit(train, feats)
    return va.fit_predict(t, test, feats)                                            # (lo, md, hi), sorted


rows = []
for i, fd in enumerate(folds, 1):
    f0 = day_idx[fd[0]]
    cal_days = days[f0 - K_CAL:f0]
    cal_train = df[df["tradingDate"] < cal_days[0]]
    cal = df[df["tradingDate"].isin(cal_days) & stock]
    train = df[df["tradingDate"] < fd[0]]
    test = df[df["tradingDate"].isin(fd) & stock]
    lo_c, _, hi_c = predict(cal_train, cal, kept)
    y_c = cal["remainingDriftPct"].to_numpy()
    score = np.maximum(lo_c - y_c, y_c - hi_c)
    if MODE == "mult":
        score = score / np.maximum(hi_c - lo_c, 1e-6)
    q = {}
    for h, g in pd.Series(score).groupby(cal["hoursSinceOpen"].to_numpy()):
        n = len(g); level = min(1.0, np.ceil((n + 1) * 0.9) / n)
        q[h] = float(np.quantile(g, level))
    lo, md, hi = predict(train, test, kept)
    qh = test["hoursSinceOpen"].map(q).fillna(0).to_numpy()
    if MODE == "mult":
        qh = qh * (hi - lo)
    out = test[["tradingDate", "instrument", "hoursSinceOpen", "remainingDriftPct"]].copy()
    out["lo"], out["hi"], out["lo_c"], out["hi_c"] = lo, hi, lo - qh, hi + qh
    rows.append(out)
    print(f"fold {i}/{va.N_FOLDS} q by hour: " + " ".join(f"h{int(h)}:{v:+.2f}" for h, v in sorted(q.items())), flush=True)
r = pd.concat(rows, ignore_index=True)
early = r[r.hoursSinceOpen <= 5]
pin = lambda y, p, a: np.maximum(a * (y - p), (a - 1) * (y - p))
print(f"\n=== {len(early)} out-of-sample rows (hours 0-5), calibration window K={K_CAL} days ===")
print(f"{'':16s} {'coverage':>9s} {'width %':>8s} {'pinball lo+hi':>14s}")
for lab, lo, hi in (("uncalibrated", "lo", "hi"), ("conformal", "lo_c", "hi_c")):
    y = early.remainingDriftPct.to_numpy()
    cov = ((y >= early[lo]) & (y <= early[hi])).mean() * 100
    pb = (pin(y, early[lo].to_numpy(), .05) + pin(y, early[hi].to_numpy(), .95)).mean() / 2
    print(f"{lab:16s} {cov:8.1f}% {(early[hi] - early[lo]).mean():8.3f} {pb:14.5f}")
print("\ncoverage by hour (target 90%):")
print(f"{'hour':>5s} {'uncal':>7s} {'conformal':>10s} {'width uncal':>12s} {'width conf':>11s}")
for h, g in early.groupby("hoursSinceOpen"):
    y = g.remainingDriftPct.to_numpy()
    print(f"{int(h):5d} {((y >= g.lo) & (y <= g.hi)).mean() * 100:6.1f}% {((y >= g.lo_c) & (y <= g.hi_c)).mean() * 100:9.1f}% {(g.hi - g.lo).mean():12.3f} {(g.hi_c - g.lo_c).mean():11.3f}")
# day-level stability of coverage
d = early.assign(u=((early.remainingDriftPct >= early.lo) & (early.remainingDriftPct <= early.hi)), c=((early.remainingDriftPct >= early.lo_c) & (early.remainingDriftPct <= early.hi_c)))
g = d.groupby("tradingDate")[["u", "c"]].mean() * 100
print(f"\nper-day coverage spread (std across {len(g)} days): uncalibrated {g.u.std():.1f} pts, conformal {g.c.std():.1f} pts; days below 80%: {int((g.u < 80).sum())} -> {int((g.c < 80).sum())}")
