"""Walk-forward ablation for the MULTI-DAY (5/10/20/40-day forward return) model: do
bigMoveVolumeInteraction and obvMomentum10d improve it out of sample?

Mirrors production (run_daily_ai_prediction.py: MULTIDAY_FEATURES, pooled model per horizon over
all 52 instruments, same hyperparameters), evaluated on the 50 basket stocks only.

Honest handling of overlapping labels: a 40-day forward return at day t shares 39/40 of its window
with day t+1, so consecutive test days are NOT independent. So:
  * each fold trains only on rows whose label window ended before the fold starts (purge of h+1 days)
  * significance uses a circular BLOCK bootstrap over test days with block length = horizon,
    never a plain per-row/per-day t-test.

Variants: A_all (production), B_no_volume (minus the two features) = the planned comparison;
C_bigmove_only / D_obv_only are exploratory.
"""
import importlib.util
import os
import urllib.parse

import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("rdap", os.path.join(HERE, "run_daily_ai_prediction.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

N_TEST_DAYS = 240
N_FOLDS = 6
VOL = ["bigMoveVolumeInteraction", "obvMomentum10d"]


def build_frame():
    symbols = m.get_symbols()
    frames = []
    for s in symbols:
        rows = m.get_json(f"/api/ml/features/{urllib.parse.quote(s)}")
        if rows:
            d = pd.DataFrame(rows)
            d["instrument"] = s
            frames.append(d)
    df = pd.concat(frames, ignore_index=True)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    df = df.sort_values(["instrument", "tradingDate"]).reset_index(drop=True)
    df["bigMoveVolumeInteraction"] = pd.to_numeric(df["dailyReturnPct"], errors="coerce") * \
        pd.to_numeric(df["volumeRatio20d"], errors="coerce")

    def obv_momentum(g):
        vol = pd.to_numeric(g["volume"], errors="coerce")
        ret = pd.to_numeric(g["dailyReturnPct"], errors="coerce")
        signed = np.where(ret > 0, vol, np.where(ret < 0, -vol, 0))
        return pd.Series(signed, index=g.index).cumsum().pct_change(10).replace([np.inf, -np.inf], np.nan)

    df["obvMomentum10d"] = df.groupby("instrument", group_keys=False).apply(obv_momentum)
    df[m.MULTIDAY_FEATURES] = df[m.MULTIDAY_FEATURES].apply(pd.to_numeric, errors="coerce")
    return df


def block_boot_means(x, block, n=2000, seed=0):
    rng = np.random.default_rng(seed)
    n_days = len(x)
    nblocks = int(np.ceil(n_days / block))
    out = np.empty(n)
    offs = np.arange(block)[None, :]
    for b in range(n):
        idx = (rng.integers(0, n_days, nblocks)[:, None] + offs) % n_days
        out[b] = x[idx.ravel()[:n_days]].mean()
    return out


def summarize_diff(per_day, block, label):
    x = per_day.to_numpy()
    boots = block_boot_means(x, block)
    mean = x.mean()
    lo, hi = np.percentile(boots, [2.5, 97.5])
    z = mean / boots.std() if boots.std() > 0 else float("nan")
    p = 2 * (1 - stats.norm.cdf(abs(z)))
    print(f"    {label:28s} A better on {100 * (x > 0).mean():5.1f}% of {len(x)} days | gain {mean:+.5f} "
          f"(95% CI {lo:+.5f}..{hi:+.5f})  z={z:+.2f}  p={p:.3f}")


def main():
    df = build_frame()
    print(f"{len(df)} rows, {df['instrument'].nunique()} instruments, {df['tradingDate'].min().date()}..{df['tradingDate'].max().date()}")
    all_feats = list(m.MULTIDAY_FEATURES)
    for f in VOL:
        assert f in all_feats
    variants = {
        "A_all": all_feats,
        "B_no_volume": [f for f in all_feats if f not in VOL],
        "C_bigmove_only": [f for f in all_feats if f != "obvMomentum10d"],
        "D_obv_only": [f for f in all_feats if f != "bigMoveVolumeInteraction"],
    }
    stocks = ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])

    for h in m.HORIZONS:
        target = f"forwardReturn{h}dPct"
        sub = df.dropna(subset=m.MULTIDAY_REQUIRED_FEATURES + [target]).copy()
        days = sorted(sub["tradingDate"].unique())
        day_idx = {d: i for i, d in enumerate(days)}
        sub["di"] = sub["tradingDate"].map(day_idx)
        test_days = days[-N_TEST_DAYS:]
        block = len(test_days) // N_FOLDS
        folds = [test_days[i * block:(i + 1) * block if i < N_FOLDS - 1 else len(test_days)] for i in range(N_FOLDS)]
        res = {k: [] for k in variants}
        for fold_days in folds:
            i0 = day_idx[fold_days[0]]
            train = sub[sub["di"] <= i0 - h - 1]          # label window ended before the fold starts
            test = sub[sub["tradingDate"].isin(fold_days) & stocks.reindex(sub.index, fill_value=False)]
            base_mean = train[target].mean()
            for name, feats in variants.items():
                model = LGBMRegressor(n_estimators=200, max_depth=4, learning_rate=0.03, subsample=0.8,
                                      colsample_bytree=0.8, random_state=42, verbosity=-1)
                model.fit(train[feats], train[target])
                out = test[["tradingDate", "instrument", target]].copy()
                out["pred"] = model.predict(test[feats])
                out["base"] = base_mean
                res[name].append(out)
        res = {k: pd.concat(v, ignore_index=True) for k, v in res.items()}
        A = res["A_all"]
        y = A[target].to_numpy()
        print(f"\n===== FORWARD_{h}D: {len(A)} stock-days over {A['tradingDate'].nunique()} test days "
              f"(block length {h} for the bootstrap) =====")
        print(f"  zero-forecast MAE {np.mean(np.abs(y)):.3f}%   train-mean-baseline MAE {np.mean(np.abs(y - A['base'])):.3f}%")
        print(f"  {'variant':15s} {'MAE%':>7s} {'vs baseline':>12s} {'dir%':>6s} {'pooled IC':>10s} {'mean daily x-sec IC':>20s}")
        for name, r in res.items():
            err = np.abs(r[target] - r["pred"])
            base_err = np.abs(r[target] - r["base"])
            direction = 100 * np.mean(np.sign(r["pred"] - r["base"]) == np.sign(r[target] - r["base"]))
            pooled_ic = stats.spearmanr(r["pred"], r[target])[0]
            daily_ic = r.groupby("tradingDate").apply(
                lambda g: stats.spearmanr(g["pred"], g[target])[0] if len(g) >= 20 else np.nan).dropna()
            print(f"  {name:15s} {err.mean():7.3f} {100 * (1 - err.mean() / base_err.mean()):11.2f}% {direction:6.1f} "
                  f"{pooled_ic:+10.4f} {daily_ic.mean():+20.4f}")
        print("  paired, positive gain = model WITH the volume features is better:")
        for a, b, lab in (("A_all", "B_no_volume", "PLANNED  A vs B"),
                          ("C_bigmove_only", "B_no_volume", "explor.  bigMove vs none"),
                          ("D_obv_only", "B_no_volume", "explor.  OBV vs none")):
            ra, rb = res[a], res[b]
            d_abs = (np.abs(rb[target] - rb["pred"]) - np.abs(ra[target] - ra["pred"]))
            d_sq = ((rb[target] - rb["pred"]) ** 2 - (ra[target] - ra["pred"]) ** 2)
            summarize_diff(pd.Series(d_abs.to_numpy()).groupby(ra["tradingDate"].to_numpy()).mean(), h, lab + " (abs err)")
            summarize_diff(pd.Series(d_sq.to_numpy()).groupby(ra["tradingDate"].to_numpy()).mean(), h, lab + " (sq err)")


if __name__ == "__main__":
    main()
