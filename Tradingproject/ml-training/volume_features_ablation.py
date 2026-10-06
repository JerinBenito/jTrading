"""Walk-forward ablation: do the three volume features (basketVolumeRankPct, prevDayVolumeRankPct,
intradayObv) actually improve the same-day-close models OUT OF SAMPLE?

Uses the exact production feature code (run_daily_ai_prediction.py: add_basket_volume_rank,
add_prev_day_volume_rank, select_intraday_features, INTRADAY_FEATURES) and production
hyperparameters, so "variant A" is the model that is live. Expanding-window folds over the most
recent N_TEST trading days: each fold trains only on days strictly before its first test day.

Variants (all trained/tested on identical rows, evaluated on basket stocks only):
  A_all          every feature in today's production fit
  B_no_volume    A minus the three volume features        <- the planned comparison (A vs B)
  C_ranks_only   A minus intradayObv                       (exploratory)
  D_obv_only     A minus the two rank features             (exploratory)

Scores: pinball loss for the 5%/50%/95% quantile models (proper scoring rule for exactly what
the models are trained on), MAE vs a "no change" (zero drift) baseline, range coverage/width.
Significance: PAIRED across trading days - stocks within a day move together, so a day, not a row,
is the independent unit.
"""
import importlib.util
import json
import os
import sys
import tempfile
import time

import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("rdap", os.path.join(HERE, "run_daily_ai_prediction.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

CACHE = os.environ.get("ABLATION_CACHE", os.path.join(tempfile.gettempdir(), "intraday_all_cache.json"))
N_TEST_DAYS = 240
N_FOLDS = 8
VOLUME_FEATURES = ["basketVolumeRankPct", "prevDayVolumeRankPct", "intradayObv"]
RANKS = ["basketVolumeRankPct", "prevDayVolumeRankPct"]
ALPHAS = (("low", 0.05), ("med", 0.5), ("high", 0.95))


def log(msg):
    print(msg, flush=True)


def load_rows():
    if os.path.exists(CACHE) and time.time() - os.path.getmtime(CACHE) < 12 * 3600:
        log(f"using cached export {CACHE}")
        with open(CACHE, "r", encoding="utf-8") as f:
            return json.load(f)
    log("downloading /api/ml/intraday-features/all (~181 MB) ...")
    rows = m.get_json("/api/ml/intraday-features/all", timeout=900)
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(rows, f)
    return rows


def build_frame():
    df = pd.DataFrame(load_rows())
    df = m.add_basket_volume_rank(df)
    df = m.add_prev_day_volume_rank(df)
    df["intradayObv"] = pd.to_numeric(df["returnSoFarPct"], errors="coerce") * \
        pd.to_numeric(df["volumeSoFarRatio"], errors="coerce")
    if "resultsDayOffset" not in df.columns:   # added to production 2026-10-06; this ablation predates it
        df["resultsDayOffset"] = np.nan
    df[m.INTRADAY_FEATURES] = df[m.INTRADAY_FEATURES].apply(pd.to_numeric, errors="coerce")
    df = df.dropna(subset=["remainingDriftPct"]).reset_index(drop=True)
    return df


def fit_predict(train, test, feats):
    preds = {}
    for name, alpha in ALPHAS:
        model = LGBMRegressor(n_estimators=300, max_depth=5, learning_rate=0.03, subsample=0.8,
                              colsample_bytree=0.8, random_state=42, verbosity=-1,
                              objective="quantile", alpha=alpha)
        model.fit(train[feats], train["remainingDriftPct"])
        preds[name] = model.predict(test[feats])
    # same crossing guard as production
    lo, md, hi = np.sort(np.vstack([preds["low"], preds["med"], preds["high"]]), axis=0)
    return lo, md, hi


def pinball(y, p, a):
    d = y - p
    return np.maximum(a * d, (a - 1) * d)


def main():
    df = build_frame()
    kept, gated = m.select_intraday_features(df)
    log(f"rows={len(df)}  features in today's production fit ({len(kept)}): {sorted(kept)}")
    for f in VOLUME_FEATURES:
        assert f in kept, f"{f} not in the production fit - ablation would be meaningless"

    variants = {
        "A_all": kept,
        "B_no_volume": [f for f in kept if f not in VOLUME_FEATURES],
        "C_ranks_only": [f for f in kept if f != "intradayObv"],
        "D_obv_only": [f for f in kept if f not in RANKS],
    }
    for k, v in variants.items():
        log(f"  {k}: {len(v)} features")

    days = sorted(df["tradingDate"].unique())
    test_days = days[-N_TEST_DAYS:]
    block = len(test_days) // N_FOLDS
    folds = [test_days[i * block:(i + 1) * block if i < N_FOLDS - 1 else len(test_days)] for i in range(N_FOLDS)]
    log(f"{len(days)} trading days total; testing on the last {len(test_days)} in {N_FOLDS} expanding folds "
        f"({test_days[0]} .. {test_days[-1]})")

    results = {k: [] for k in variants}
    t0 = time.time()
    for i, fold_days in enumerate(folds, 1):
        first = fold_days[0]
        train = df[df["tradingDate"] < first]
        test = df[df["tradingDate"].isin(fold_days) & ~df["instrument"].isin(["NIFTY", "BANKNIFTY"])]
        log(f"fold {i}/{N_FOLDS}: train through {train['tradingDate'].max()} ({len(train)} rows) -> "
            f"test {fold_days[0]}..{fold_days[-1]} ({len(test)} stock-hour rows)  [{time.time() - t0:.0f}s]")
        for name, feats in variants.items():
            lo, md, hi = fit_predict(train, test, feats)
            out = test[["tradingDate", "instrument", "hoursSinceOpen", "remainingDriftPct"]].copy()
            out["lo"], out["md"], out["hi"] = lo, md, hi
            results[name].append(out)

    res = {k: pd.concat(v, ignore_index=True) for k, v in results.items()}
    base = res["A_all"]
    y = base["remainingDriftPct"].to_numpy()

    log("\n================ POOLED OUT-OF-SAMPLE RESULTS (basket stocks) ================")
    log(f"{len(base)} stock-hour rows over {base['tradingDate'].nunique()} test days")
    mae_zero = np.mean(np.abs(y))
    log(f"no-change baseline MAE: {mae_zero:.4f}%")
    rows = []
    for name, r in res.items():
        yy = r["remainingDriftPct"].to_numpy()
        md, lo, hi = r["md"].to_numpy(), r["lo"].to_numpy(), r["hi"].to_numpy()
        mae = np.mean(np.abs(yy - md))
        pin = np.mean(pinball(yy, lo, 0.05) + pinball(yy, md, 0.5) + pinball(yy, hi, 0.95)) / 3
        cover = np.mean((yy >= lo) & (yy <= hi)) * 100
        width = np.mean(hi - lo)
        nz = np.abs(yy) > 1e-9
        direction = np.mean(np.sign(md[nz]) == np.sign(yy[nz])) * 100
        ic = stats.spearmanr(md, yy)[0]
        rows.append((name, mae, 100 * (1 - mae / mae_zero), pin, cover, width, direction, ic))
    log(f"{'variant':14s} {'MAE%':>8s} {'vs no-chg':>10s} {'pinball':>9s} {'range hit%':>11s} {'width%':>8s} {'dir%':>7s} {'IC':>8s}")
    for name, mae, imp, pin, cover, width, direction, ic in rows:
        log(f"{name:14s} {mae:8.4f} {imp:9.2f}% {pin:9.5f} {cover:10.1f}% {width:8.3f} {direction:6.1f}% {ic:+8.4f}")

    def paired(a_name, b_name, label, fn):
        ra, rb = res[a_name], res[b_name]
        diff = fn(rb) - fn(ra)          # positive = A better (lower loss)
        per_day = pd.Series(diff).groupby(ra["tradingDate"].to_numpy()).mean()
        n = len(per_day)
        mean = per_day.mean()
        t = mean / (per_day.std(ddof=1) / np.sqrt(n)) if per_day.std(ddof=1) > 0 else float("nan")
        p = 2 * (1 - stats.t.cdf(abs(t), n - 1))
        rng = np.random.default_rng(0)
        boots = [per_day.sample(n, replace=True, random_state=int(s)).mean() for s in rng.integers(0, 10**6, 2000)]
        lo_ci, hi_ci = np.percentile(boots, [2.5, 97.5])
        log(f"  {label:34s} A better on {100 * (per_day > 0).mean():5.1f}% of {n} days | mean gain {mean:+.6f} "
            f"(95% CI {lo_ci:+.6f}..{hi_ci:+.6f})  t={t:+.2f}  p={p:.3f}")

    abs_err = lambda r: np.abs(r["remainingDriftPct"].to_numpy() - r["md"].to_numpy())
    pin_all = lambda r: (pinball(r["remainingDriftPct"].to_numpy(), r["lo"].to_numpy(), 0.05)
                         + pinball(r["remainingDriftPct"].to_numpy(), r["md"].to_numpy(), 0.5)
                         + pinball(r["remainingDriftPct"].to_numpy(), r["hi"].to_numpy(), 0.95)) / 3
    late = lambda fn: (lambda r: fn(r) * (r["hoursSinceOpen"].to_numpy() >= 3))

    log("\n---- PAIRED across test days (positive gain = the model WITH the features is better) ----")
    log("PLANNED comparison, A (all) vs B (no volume):")
    paired("A_all", "B_no_volume", "point error (MAE)", abs_err)
    paired("A_all", "B_no_volume", "quantile pinball (point+range)", pin_all)
    log("exploratory (not corrected for multiple looks):")
    paired("C_ranks_only", "B_no_volume", "ranks only vs none, pinball", pin_all)
    paired("D_obv_only", "B_no_volume", "intradayObv only vs none, pinball", pin_all)

    log("\n---- by SINGLE hour since open, A vs B (hour 6 = final candle: remaining move is exactly 0) ----")
    log(f"{'hour':>4s} {'n':>6s} {'|y|':>8s} {'MAE A':>8s} {'MAE 0':>8s} {'pinA':>9s} {'pinB':>9s} {'A vs B':>8s} {'hitA':>6s} {'hitB':>6s} {'width':>7s}")
    cov = lambda r: np.mean((r["remainingDriftPct"] >= r["lo"]) & (r["remainingDriftPct"] <= r["hi"])) * 100
    for h in sorted(base["hoursSinceOpen"].unique()):
        sel = (base["hoursSinceOpen"] == h).to_numpy()
        ra, rb = res["A_all"][sel], res["B_no_volume"][sel]
        yy = ra["remainingDriftPct"].to_numpy()
        pa, pb = pin_all(ra), pin_all(rb)
        rel = 100 * (1 - pa.mean() / pb.mean()) if pb.mean() > 0 else float("nan")
        log(f"{int(h):4d} {sel.sum():6d} {np.abs(yy).mean():8.4f} {np.abs(yy - ra['md'].to_numpy()).mean():8.4f} "
            f"{np.abs(yy).mean():8.4f} {pa.mean():9.5f} {pb.mean():9.5f} {rel:+7.2f}% {cov(ra):5.1f}% {cov(rb):5.1f}% {(ra['hi'] - ra['lo']).mean():7.3f}")
    nz = (base["hoursSinceOpen"] <= 5).to_numpy()
    ra, rb = res["A_all"][nz], res["B_no_volume"][nz]
    log("\nPLANNED comparison again, hours 0-5 only (drops the trivial final-candle rows):")
    for label, fn in (("point error (MAE)", abs_err), ("quantile pinball (point+range)", pin_all)):
        diff = fn(rb) - fn(ra)
        per_day = pd.Series(diff).groupby(ra["tradingDate"].to_numpy()).mean()
        n = len(per_day)
        mean = per_day.mean()
        t = mean / (per_day.std(ddof=1) / np.sqrt(n))
        log(f"  {label:34s} A better on {100 * (per_day > 0).mean():5.1f}% of {n} days | mean gain {mean:+.6f}  "
            f"t={t:+.2f}  p={2 * (1 - stats.t.cdf(abs(t), n - 1)):.3f}")
    pa, pb = pin_all(ra).mean(), pin_all(rb).mean()
    log(f"  hours 0-5 pooled pinball: A={pa:.5f}  B={pb:.5f}  -> A is {100 * (1 - pa / pb):+.2f}% vs B")
    log(f"\ndone in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
