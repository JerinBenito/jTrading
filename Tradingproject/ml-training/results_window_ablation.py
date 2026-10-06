"""Walk-forward: does a 'trading-day offset from the stock's quarterly results date' feature improve the
production same-day quantile model (A = production features, B = A + resultsDayOffset)? Same harness as
volume_features_ablation.py (expanding folds over the last 240 days, pinball, paired across days).
Feature: offset in trading days from the first trading day on/after the results date, clipped to -3..+3,
NaN elsewhere. Known in advance on the day (calendar), no look-ahead. Caveat: historical dates are
actual dates; live use would rely on announced/estimated dates (Yahoo's future dates can be wrong).
"""
import os, sys, time
import numpy as np, pandas as pd
from scipy import stats
sys.path.insert(0, os.path.dirname(__file__))
import volume_features_ablation as va

ev = pd.read_csv(sys.argv[1]); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10])
ev["instrument"] = ev["instrument"].replace({"TMPV": "TATAMOTORS"})  # intraday export may use either; map both below
df = va.build_frame()
df["tradingDate"] = df["tradingDate"].astype(str)
instr = set(df.instrument.unique())
print("TATAMOTORS in export:", "TATAMOTORS" in instr, " TMPV:", "TMPV" in instr)
if "TMPV" in instr: ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
off = pd.Series(np.nan, index=df.index)
for s, g in df.groupby("instrument"):
    days = pd.Series(sorted(g.tradingDate.unique())); dts = pd.to_datetime(days)
    ds = ev[ev.instrument == s]["date"].tolist()
    o = pd.Series(np.nan, index=days.values)
    for e in ds:
        pos = dts.searchsorted(e)
        if pos >= len(days) or (dts[pos] - e) > pd.Timedelta(days=4): continue
        for k in range(-3, 4):
            if 0 <= pos + k < len(days): o[days[pos + k]] = k
    off.loc[g.index] = g.tradingDate.map(o).values
df["resultsDayOffset"] = off
inwin = df["resultsDayOffset"].isin([0, 1])
print(f"rows with an offset: {df.resultsDayOffset.notna().mean() * 100:.1f}%   rows on offset 0/+1: {inwin.mean() * 100:.1f}%")

kept, _ = va.m.select_intraday_features(df)
kept = [f for f in kept if f != "resultsDayOffset"]   # production now includes it; A is the model WITHOUT
variants = {"A_prod": kept, "B_plus_results": kept + ["resultsDayOffset"]}
days = sorted(df["tradingDate"].unique()); test_days = days[-va.N_TEST_DAYS:]
block = len(test_days) // va.N_FOLDS
folds = [test_days[i * block:(i + 1) * block if i < va.N_FOLDS - 1 else len(test_days)] for i in range(va.N_FOLDS)]
res = {k: [] for k in variants}; t0 = time.time()
for i, fd in enumerate(folds, 1):
    train = df[df["tradingDate"] < fd[0]]
    test = df[df["tradingDate"].isin(fd) & ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])]
    print(f"fold {i}/{va.N_FOLDS} [{time.time() - t0:.0f}s]", flush=True)
    for name, feats in variants.items():
        lo, md, hi = va.fit_predict(train, test, feats)
        out = test[["tradingDate", "instrument", "hoursSinceOpen", "remainingDriftPct", "resultsDayOffset"]].copy()
        out["lo"], out["md"], out["hi"] = lo, md, hi
        res[name].append(out)
res = {k: pd.concat(v, ignore_index=True) for k, v in res.items()}
A, B = res["A_prod"], res["B_plus_results"]
pin = lambda r: (va.pinball(r.remainingDriftPct.to_numpy(), r.lo.to_numpy(), .05) + va.pinball(r.remainingDriftPct.to_numpy(), r.md.to_numpy(), .5)
                 + va.pinball(r.remainingDriftPct.to_numpy(), r.hi.to_numpy(), .95)) / 3
cover = lambda r: np.mean((r.remainingDriftPct >= r.lo) & (r.remainingDriftPct <= r.hi)) * 100
early = (A.hoursSinceOpen <= 5).to_numpy()   # drop the trivial final candle
print("\n=== hours 0-5 rows; pinball lower = better; target coverage 90% ===")
print(f"{'subset':26s} {'n':>7s} {'pin A':>9s} {'pin B':>9s} {'B vs A':>8s} {'cover A':>8s} {'cover B':>8s} {'width A':>8s} {'width B':>8s}")
for lab, mask in (("all rows", early), ("results day 0/+1", early & A.resultsDayOffset.isin([0, 1]).to_numpy()),
                  ("results day +1 only", early & (A.resultsDayOffset == 1).to_numpy()),
                  ("results day 0 only", early & (A.resultsDayOffset == 0).to_numpy()),
                  ("all other rows", early & ~A.resultsDayOffset.isin([0, 1]).to_numpy())):
    a, b = A[mask], B[mask]
    print(f"{lab:26s} {mask.sum():7d} {pin(a).mean():9.5f} {pin(b).mean():9.5f} {100 * (1 - pin(b).mean() / pin(a).mean()):+7.2f}% "
          f"{cover(a):7.1f}% {cover(b):7.1f}% {(a.hi - a.lo).mean():8.3f} {(b.hi - b.lo).mean():8.3f}")
a, b = A[early], B[early]
diff = pd.Series(pin(a) - pin(b)).groupby(a.tradingDate.to_numpy()).mean()
n = len(diff); t = diff.mean() / (diff.std(ddof=1) / np.sqrt(n))
print(f"\npaired across {n} test days (positive = B better): mean {diff.mean():+.6f}, B better on {100 * (diff > 0).mean():.1f}% of days, t={t:+.2f}, p={2 * (1 - stats.t.cdf(abs(t), n - 1)):.3f}")
w = a.resultsDayOffset.isin([0, 1]).to_numpy()
dw = pd.Series((pin(a) - pin(b))[w]).groupby(a.tradingDate.to_numpy()[w]).mean()
print(f"on results-window days only ({len(dw)} days with events): mean gain {dw.mean():+.6f}, B better on {100 * (dw > 0).mean():.1f}% of days, "
      f"t={dw.mean() / (dw.std(ddof=1) / np.sqrt(len(dw))):+.2f}")
