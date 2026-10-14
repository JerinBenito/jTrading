"""Two questions about the MULTI-DAY (5/10/20/40-day forward return) model, answered by replaying production AS OF
each recent day D (train only on rows whose label window ended before D, production hyperparameters):

 A. YOUNG day-constant features (pcrLatest, deterministicDeviationPct, hmm/garch, global*, recentPattern*, VIX): the
    multi-day model has no history gate (the same-day model's lean came from exactly these). Compare predictions and
    realised accuracy with vs without them.
 B. PARTIAL-BAR skew: the live multi-day row is today's stored daily bar, which the morning run snapshots at ~10:25 IST
    (close/range/volume/body/wicks partial) while training rows are complete days. Rebuild each historical day's
    morning-style features from its first hourly candle (volatilitySoFar, body/wicks, volumeSoFarRatio, price at the end
    of the first hour; RSI/EMA spread/returns/OBV recomputed on that close; formula differences cancelled by adding
    (my partial - my complete) to the stored complete value) and score the SAME model on them.

Reports per horizon: mean predicted return, how far partial vs complete predictions diverge, and realised cross-sectional
rank IC / MAE vs the training-mean baseline on days whose labels exist. Day is the unit; stocks move together.
"""
import os
import sys

import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats

sys.path.insert(0, os.path.dirname(__file__))
import multiday_volume_ablation as mv
import volume_features_ablation as va

m = mv.m
HORIZONS = (5, 10)
N_TEST_DAYS = 30
YOUNG = ["deterministicDeviationPct", "hmmDeviationPct", "garchRangeWidthPct", "pcrLatest", "globalSp500ChangePct",
         "globalCrudeOilChangePct", "globalUsdInrChangePct", "globalIndiaVixLevel", "recentPatternWinRate", "recentPatternDirection"]

df = mv.build_frame()
df["tradingDate"] = pd.to_datetime(df["tradingDate"])
stock = ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])
for c in ("open", "high", "low", "close", "volume"):
    df[c] = pd.to_numeric(df[c], errors="coerce")
days = sorted(df["tradingDate"].unique())
day_idx = {d: i for i, d in enumerate(days)}
df["di"] = df["tradingDate"].map(day_idx)

# first-hour snapshot per (instrument, day) from the intraday export
intr = pd.DataFrame(va.load_rows())
intr["tradingDate"] = pd.to_datetime(intr["tradingDate"])
h0 = intr[intr.hoursSinceOpen == 0].set_index(["instrument", "tradingDate"])
for c in ("returnSoFarPct", "volatilitySoFarPct", "bodyPct", "upperWickPct", "lowerWickPct", "volumeSoFarRatio", "currentPrice"):
    h0[c] = pd.to_numeric(h0[c], errors="coerce")


def rsi_last(c):
    dl = c.diff()
    up, dn = dl.clip(lower=0).ewm(alpha=1 / 14, adjust=False).mean(), (-dl.clip(upper=0)).ewm(alpha=1 / 14, adjust=False).mean()
    return float(100 - 100 / (1 + up.iloc[-1] / dn.iloc[-1])) if dn.iloc[-1] > 0 else 100.0


def ema_spread_last(c):
    return float((c.ewm(span=9, adjust=False).mean().iloc[-1] - c.ewm(span=21, adjust=False).mean().iloc[-1]) / c.iloc[-1] * 100)


def partial_features(g, i):
    """Morning-style feature dict for row i of one stock's chronological frame g (None if the first-hour snapshot is missing)."""
    r = g.iloc[i]
    key = (r["instrument"], r["tradingDate"])
    if key not in h0.index or i < 45:
        return None
    s = h0.loc[key]
    if np.isnan(s["currentPrice"]) or np.isnan(s["volumeSoFarRatio"]) or np.isnan(r["volumeRatio20d"]) or r["volumeRatio20d"] <= 0:
        return None
    pc, prev_close = float(s["currentPrice"]), float(g["close"].iloc[i - 1])
    c_complete = g["close"].iloc[: i + 1].reset_index(drop=True)
    c_partial = pd.concat([g["close"].iloc[:i].reset_index(drop=True), pd.Series([pc])], ignore_index=True)
    f = {}
    f["dailyReturnPct"] = (pc / prev_close - 1) * 100
    f["intradayRangePct"] = float(s["volatilitySoFarPct"])
    f["bodyPct"], f["upperWickPct"], f["lowerWickPct"] = float(s["bodyPct"]), float(s["upperWickPct"]), float(s["lowerWickPct"])
    f["volumeRatio20d"] = float(s["volumeSoFarRatio"])
    f["rsi14"] = float(r["rsi14"]) + rsi_last(c_partial) - rsi_last(c_complete)
    f["emaSpreadPct"] = float(r["emaSpreadPct"]) + ema_spread_last(c_partial) - ema_spread_last(c_complete)
    f["atr14"] = float(g["atr14"].iloc[i - 1])
    for n in (5, 10, 20, 40):
        f[f"return{n}dPct"] = (pc / float(g["close"].iloc[i - n]) - 1) * 100
    f["bigMoveVolumeInteraction"] = f["dailyReturnPct"] * f["volumeRatio20d"]
    avg20 = float(r["volume"]) / float(r["volumeRatio20d"])
    vol = pd.to_numeric(g["volume"], errors="coerce").iloc[: i + 1].to_numpy(dtype=float).copy()
    ret = pd.to_numeric(g["dailyReturnPct"], errors="coerce").iloc[: i + 1].to_numpy(dtype=float).copy()
    vol[-1], ret[-1] = f["volumeRatio20d"] * avg20, f["dailyReturnPct"]
    signed = np.where(ret > 0, vol, np.where(ret < 0, -vol, 0))
    obv = pd.Series(signed).cumsum()
    f["obvMomentum10d"] = float((obv.iloc[-1] - obv.iloc[-11]) / obv.iloc[-11]) if obv.iloc[-11] != 0 else np.nan
    return f


# build partial rows for the test days
test_days = days[-N_TEST_DAYS - 1:-1]          # complete days only (the last row is today's partial stored bar)
partial_rows = {}
for sym, g in df[stock].groupby("instrument"):
    g = g.sort_values("tradingDate").reset_index(drop=True)
    for i in range(len(g)):
        if g.loc[i, "tradingDate"] in test_days:
            f = partial_features(g, i)
            if f is not None:
                partial_rows[(sym, g.loc[i, "tradingDate"])] = f
print(f"partial-bar rows rebuilt: {len(partial_rows)} (stock, day) pairs across {len(test_days)} test days")

ALL = list(m.MULTIDAY_FEATURES)
NOYOUNG = [f for f in ALL if f not in YOUNG]
out = []
for h in HORIZONS:
    target = f"forwardReturn{h}dPct"
    for D in test_days:
        di = day_idx[D]
        train = df[(df["di"] <= di - h - 1) & df[target].notna()].dropna(subset=m.MULTIDAY_REQUIRED_FEATURES)
        test = df[(df["tradingDate"] == D) & stock].dropna(subset=m.MULTIDAY_REQUIRED_FEATURES)
        if len(train) < 5000 or len(test) < 30:
            continue
        mods = {}
        for name, feats in (("P", ALL), ("X", NOYOUNG)):
            mod = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)
            mod.fit(train[feats], train[target]); mods[name] = mod
        base = float(train[target].mean())
        pc, px = mods["P"].predict(test[ALL]), mods["X"].predict(test[NOYOUNG])
        tp = test.copy()
        keep = []
        for k, (_, r) in enumerate(tp.iterrows()):
            f = partial_rows.get((r["instrument"], r["tradingDate"]))
            if f is None:
                keep.append(False); continue
            for col, v in f.items():
                tp.iloc[k, tp.columns.get_loc(col)] = v
            keep.append(True)
        keep = np.array(keep)
        pp = mods["P"].predict(tp[ALL]) if keep.any() else np.full(len(tp), np.nan)
        y = test[target].to_numpy()
        for j in range(len(test)):
            out.append(dict(h=h, D=D, y=y[j], base=base, complete=pc[j], noyoung=px[j], partial=pp[j] if keep[j] else np.nan))
    print(f"horizon {h}d done", flush=True)
r = pd.DataFrame(out)
for h in HORIZONS:
    x = r[r.h == h]
    lab = x[x.y.notna()]
    print(f"\n===== FORWARD_{h}D: {x.D.nunique()} replayed days, {lab.D.nunique()} with realised labels =====")
    print(f"  mean predicted return: complete {x.complete.mean():+.2f}%  no-young {x.noyoung.mean():+.2f}%  partial-bar {x.partial.mean():+.2f}%  (train-mean baseline {x.base.mean():+.2f}%)")
    print(f"  share predicted BELOW the training mean: complete {100 * (x.complete < x.base).mean():.0f}%  no-young {100 * (x.noyoung < x.base).mean():.0f}%  partial {100 * (x.partial < x.base).mean():.0f}%")
    both = x.dropna(subset=["partial"])
    print(f"  partial vs complete predictions: per-row corr {np.corrcoef(both.partial, both.complete)[0, 1]:+.2f}, mean |difference| {np.abs(both.partial - both.complete).mean():.2f} pts "
          f"(spread of complete predictions: std {both.complete.std():.2f})")
    print(f"  {'variant':10s} {'MAE':>7s} {'vs baseline':>12s} {'mean daily x-sec rank IC':>26s} {'IC>0 days':>10s}")
    base_mae = (lab.y - lab.base).abs().mean()
    for v in ("complete", "noyoung", "partial"):
        d = lab.dropna(subset=[v])
        mae = (d.y - d[v]).abs().mean()
        ic = d.groupby("D").apply(lambda g: stats.spearmanr(g[v], g.y)[0] if len(g) > 20 else np.nan).dropna()
        print(f"  {v:10s} {mae:7.3f} {100 * (1 - mae / (d.y - d.base).abs().mean()):11.2f}% {ic.mean():+17.3f} (n={len(ic)}) {int((ic > 0).sum()):5d}/{len(ic)}")
    print(f"  baseline (training mean) MAE {base_mae:.3f}")
