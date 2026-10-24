"""Can 1-MINUTE information sharpen the same-day RISK forecast?

Target at each decision time k (the app's hourly checkpoints 10:14, 11:14, 12:14, 13:14, 14:14): the REMAINING SWING =
(highest high - lowest low from the next minute to the close) / current price, in %. This is the quantity a trader
actually needs ('how far can this still move today?'), unlike the close-price quantiles the current model predicts.

Variants (all LightGBM, same hyperparameters, walk-forward, last N_TEST days in 40-day folds, day = unit):
  naive   : prior-day range x sqrt(fraction of the day left), one scalar fitted on the training window
  hourly  : what the current system can see - return/range so far, last-60-minute range, volume ratio so far, prior ranges,
            gap, market-wide range so far, results flag, time of day
  minute  : hourly + minute-level inputs - std of 1-minute returns, last 15/30-minute ranges, position inside today's range,
            distance from today's high/low, market-wide last-30-minute range
Reports MAE / rank correlation, how wide a 90% cushion each needs (sharpness at equal coverage) and paired-by-day tests.
Usage: python intraday_risk_test.py <earnings_dates.csv>
"""
import os, sys
import numpy as np, pandas as pd
from lightgbm import LGBMRegressor
from scipy import stats

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import minute_store as ms

N_TEST = int(os.environ.get("N_TEST", 600))
KS = (59, 119, 179, 239, 299)          # end of the minute candle ~10:14, 11:14, 12:14, 13:14, 14:14
EOD = 374
EARN = sys.argv[1]
ev = pd.read_csv(EARN); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]).dt.date
results = {s: set(x["date"]) for s, x in ev.groupby("instrument")}


def build():
    rows = []
    for sym in [s for s in ms.symbols() if s not in ("NIFTY", "BANKNIFTY")]:
        d = ms.load_minute(sym)
        arrs = {}
        for day, g in d.groupby("day"):
            if len(g) == 375:
                arrs[day] = tuple(g[k].to_numpy(float) for k in ("open", "high", "low", "close", "volume"))
        days = sorted(arrs)
        full_rng, prev_close = {}, {}
        cumv_hist = {k: [] for k in KS}
        last_close = None
        res_days = results.get(sym, set())
        for di, day in enumerate(days):
            o, h, l, c, v = arrs[day]
            full_rng[day] = (h.max() - l.min()) / o[0] * 100
            hist = [full_rng[x] for x in days[max(0, di - 20):di]]
            cumv = np.cumsum(v)
            hi_run, lo_run = np.maximum.accumulate(h), np.minimum.accumulate(l)
            rem_hi = np.maximum.accumulate(h[::-1])[::-1]
            rem_lo = np.minimum.accumulate(l[::-1])[::-1]
            lr = np.diff(np.log(c), prepend=np.log(o[0]))
            for k in KS:
                vr = cumv[k] / np.mean(cumv_hist[k][-20:]) if len(cumv_hist[k]) >= 10 and np.mean(cumv_hist[k][-20:]) > 0 else np.nan
                if len(hist) >= 20 and last_close is not None and k < EOD:
                    cp = c[k]
                    rng_so_far = (hi_run[k] - lo_run[k]) / o[0] * 100
                    rows.append(dict(
                        sym=sym, day=day, k=k,
                        ret=(cp / o[0] - 1) * 100, rng=rng_so_far,
                        last60=(h[k - 59:k + 1].max() - l[k - 59:k + 1].min()) / cp * 100,
                        last30=(h[k - 29:k + 1].max() - l[k - 29:k + 1].min()) / cp * 100,
                        last15=(h[k - 14:k + 1].max() - l[k - 14:k + 1].min()) / cp * 100,
                        std1m=lr[max(1, k - 59):k + 1].std() * 100,
                        pos=(cp - lo_run[k]) / (hi_run[k] - lo_run[k]) if hi_run[k] > lo_run[k] else 0.5,
                        dd_hi=(hi_run[k] / cp - 1) * 100, up_lo=(cp / lo_run[k] - 1) * 100,
                        vol_ratio=vr, prev_rng=full_rng[days[di - 1]], r5=np.mean(hist[-5:]), r20=np.mean(hist[-20:]),
                        gap=(o[0] / last_close - 1) * 100, results=float(day in res_days),
                        target=(rem_hi[k + 1] - rem_lo[k + 1]) / cp * 100,
                        rem_frac=(EOD - k) / 375))
                cumv_hist[k].append(cumv[k])
            last_close = c[-1]
    d = pd.DataFrame(rows)
    mk = d.groupby(["day", "k"])[["rng", "last30"]].transform("mean")
    d["mkt_rng"], d["mkt_last30"] = mk["rng"], mk["last30"]
    return d.dropna(subset=["vol_ratio"]).reset_index(drop=True)


F_HOURLY = ["k", "ret", "rng", "last60", "vol_ratio", "prev_rng", "r5", "r20", "gap", "mkt_rng", "results"]
F_MINUTE = F_HOURLY + ["std1m", "last15", "last30", "pos", "dd_hi", "up_lo", "mkt_last30"]
PARAMS = dict(n_estimators=300, max_depth=4, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=42, verbosity=-1)

if __name__ == "__main__":
    d = build()
    print(f"{len(d):,} stock-day-checkpoints, {d.day.nunique()} days; mean remaining swing {d.target.mean():.2f}%", flush=True)
    days = sorted(d["day"].unique()); test_days = days[-N_TEST:]
    folds = [test_days[i * 40:(i + 1) * 40] for i in range(N_TEST // 40)]
    out = []
    for i, fd in enumerate(folds, 1):
        tr = d[d["day"] < fd[0]]; te = d[d["day"].isin(fd)].copy()
        sc = (tr.target / (tr.prev_rng * np.sqrt(tr.rem_frac))).median()
        te["naive"] = te.prev_rng * np.sqrt(te.rem_frac) * sc
        for name, feats in (("hourly", F_HOURLY), ("minute", F_MINUTE)):
            te[name] = LGBMRegressor(**PARAMS).fit(tr[feats], tr.target).predict(te[feats])
            te[name + "_q90"] = LGBMRegressor(objective="quantile", alpha=0.9, **PARAMS).fit(tr[feats], tr.target).predict(te[feats])
        out.append(te); print(f"fold {i}/{len(folds)}", flush=True)
    r = pd.concat(out, ignore_index=True)
    base = (r.target - r.target.mean()).abs().mean()
    print(f"\n=== remaining swing, {len(r):,} out-of-sample stock-day-checkpoints over {r.day.nunique()} days ===")
    print(f"{'forecast':8s} {'MAE':>7s} {'vs avg':>8s} {'Spearman':>9s} | {'q90 cover':>9s} {'cushion x for 90%':>18s}")
    for k in ("naive", "hourly", "minute"):
        mae = (r.target - r[k]).abs().mean()
        extra = ""
        if k != "naive":
            cov = (r.target <= r[k + "_q90"]).mean() * 100
            cush = np.quantile(r.target / r[k].clip(lower=0.05), 0.9)
            extra = f"| {cov:8.1f}% {cush:17.2f}x  (mean q90 {r[k + '_q90'].mean():.2f}% vs mean forecast {r[k].mean():.2f}%)"
        print(f"{k:8s} {mae:7.3f} {100 * (1 - mae / base):7.1f}% {stats.spearmanr(r[k], r.target)[0]:+9.3f} {extra}")
    print("\nby checkpoint (MAE hourly -> minute, Spearman hourly -> minute):")
    for k, g in r.groupby("k"):
        print(f"  {(9 * 60 + 15 + k + 1) // 60:02d}:{(9 * 60 + 15 + k + 1) % 60:02d}  MAE {(g.target - g.hourly).abs().mean():.3f} -> {(g.target - g.minute).abs().mean():.3f} "
              f"| rho {stats.spearmanr(g.hourly, g.target)[0]:+.3f} -> {stats.spearmanr(g.minute, g.target)[0]:+.3f} | naive rho {stats.spearmanr(g.naive, g.target)[0]:+.3f}")
    dd = r.assign(a=(r.target - r.hourly).abs(), b=(r.target - r.minute).abs()).groupby("day")[["a", "b"]].mean()
    diff = dd.a - dd.b; t = diff.mean() / (diff.std(ddof=1) / np.sqrt(len(diff)))
    print(f"\nminute vs hourly, paired over {len(diff)} days: minute better on {100 * (diff > 0).mean():.0f}% of days, mean gain {diff.mean():.4f} pts, t={t:+.2f}")
    for yr, g in r.groupby(pd.to_datetime(r.day).dt.year):
        print(f"  {yr}: MAE hourly {(g.target - g.hourly).abs().mean():.3f} vs minute {(g.target - g.minute).abs().mean():.3f}  (n={len(g)})")
