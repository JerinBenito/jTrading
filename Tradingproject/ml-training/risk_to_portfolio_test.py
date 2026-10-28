"""Does the risk forecast turn into better RESULTS when used to size a portfolio of the 50 basket stocks, net of costs?
Walk-forward expected next-day range (production model: base features + implied vol, 600 test days) -> portfolio weights set at each
close and held for the next day's close-to-close return.  Everything is long-only, no leverage beyond 1.0x total, no direction view.
  EW        equal weight in all 50 stocks, rebalanced every REBAL days                       (the benchmark)
  INVRISK   weight proportional to 1 / forecast range (calmer stocks get more)                (fully invested)
  INVTRAIL  same but with the trailing 20-day range instead of the model                        (does the MODEL add anything?)
  TIMED     EW, but total exposure scaled down when the basket's forecast range is high         (cash otherwise, 0% return)
Costs: COST_BPS per unit of one-way turnover (STT + stamp + brokerage + slippage on Indian delivery trades is roughly 10-25 bps one-way).
Usage: python risk_to_portfolio_test.py <earnings_dates.csv>
"""
import os, sys
import numpy as np, pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import risk_forecast as rf
import iv_live
import multiday_volume_ablation as mv

N_TEST = int(os.environ.get("N_TEST", 600))
raw = mv.build_frame(); raw["tradingDate"] = pd.to_datetime(raw["tradingDate"])
raw = raw[~raw["instrument"].isin(["NIFTY", "BANKNIFTY"])]
for c in ("open", "high", "low", "close", "volumeRatio20d", "gapFromPrevClosePct"):
    raw[c] = pd.to_numeric(raw[c], errors="coerce")
raw["range"] = (raw["high"] - raw["low"]) / raw["open"] * 100; raw["gap"] = raw["gapFromPrevClosePct"]
ev = pd.read_csv(sys.argv[1]); ev["date"] = pd.to_datetime(ev["Earnings Date"].str[:10]); ev["instrument"] = ev["instrument"].replace({"TATAMOTORS": "TMPV"})
results = {s: list(x["date"].dt.date) for s, x in ev.groupby("instrument")}
frames = {s: g[["tradingDate", "open", "close", "range", "volumeRatio20d", "gap"]] for s, g in raw.groupby("instrument")}
d = rf.prepare(frames, results)
d = iv_live.attach(d, iv_live.load_history())
IVF = rf.FEATS + iv_live.IV4
d = d.dropna(subset=IVF + ["target_range", "target_absret"]).reset_index(drop=True)
days = sorted(d["tradingDate"].unique()); test_days = days[-N_TEST:]
out = []
for i in range(N_TEST // 40):
    fd = test_days[i * 40:(i + 1) * 40]
    tr = d[d["tradingDate"] < fd[0] - pd.Timedelta(days=2)]; te = d[d["tradingDate"].isin(fd)].copy()
    te["fc"] = rf.predict(rf.fit(tr, IVF), te)[0]
    out.append(te)
r = pd.concat(out, ignore_index=True)
# next-day close-to-close return per stock (the target day is next_date)
px = raw.pivot(index="tradingDate", columns="instrument", values="close").sort_index()
nxt = px.shift(-1) / px - 1
r["ret_next"] = [nxt.at[t, s] if t in nxt.index and s in nxt.columns else np.nan for t, s in zip(r.tradingDate, r.instrument)]
r = r.dropna(subset=["ret_next", "fc", "r20"])
r = r[(r.ret_next.abs() < 0.25)]                                  # drop corporate-action artefacts
F = r.pivot(index="tradingDate", columns="instrument", values="fc")
T = r.pivot(index="tradingDate", columns="instrument", values="r20")
R = r.pivot(index="tradingDate", columns="instrument", values="ret_next")
ok = F.notna() & R.notna() & T.notna()                          # per day, only stocks with a full reading are held
F, T, R = F.where(ok), T.where(ok), R.where(ok)
keep = ok.sum(axis=1) >= 40
F, T, R = F[keep], T[keep], R[keep]
print(f"{len(F)} test days, {int(ok.sum(axis=1)[keep].mean())} stocks per day on average, {F.index.min().date()}..{F.index.max().date()}")


def run(weights_fn, rebal, cost_bps):
    w_prev = pd.Series(0.0, index=R.columns); w = w_prev.copy(); rets = []; turn = []
    for k, t in enumerate(R.index):
        if k % rebal == 0:
            w_new = weights_fn(t)
        else:                                                    # drift: no trading between rebalances
            w_new = w
        tv = (w_new - w_prev).abs().sum() if k % rebal == 0 else 0.0
        gross = float((w_new * R.loc[t].fillna(0.0)).sum())
        rets.append(gross - tv * cost_bps / 1e4); turn.append(tv)
        w_prev = w_new * (1 + R.loc[t].fillna(0.0)) / (1 + gross) if (1 + gross) != 0 else w_new   # weights drift with returns
        w = w_prev
    return pd.Series(rets, index=R.index), np.mean(turn)


def ew(t): a = R.loc[t].notna().astype(float); return a / a.sum()
def invrisk(t): x = (1.0 / F.loc[t]).fillna(0.0); return x / x.sum()
def invtrail(t): x = (1.0 / T.loc[t]).fillna(0.0); return x / x.sum()
med = F.mean(axis=1).median()
def timed(t):
    expo = float(np.clip(med / F.loc[t].mean(), 0.3, 1.0))      # never more than 100% invested
    return ew(t) * expo


def metrics(x):
    eq = (1 + x).cumprod(); yrs = len(x) / 252
    return (eq.iloc[-1] ** (1 / yrs) - 1) * 100, x.std() * np.sqrt(252) * 100, x.mean() / x.std() * np.sqrt(252), (eq / eq.cummax() - 1).min() * 100


for rebal in (1, 5):
    for cost in (0, 15):
        print(f"\n--- rebalance every {rebal} day(s), cost {cost} bps per unit one-way turnover ---")
        print(f"{'strategy':10s} {'CAGR%':>7s} {'vol%':>6s} {'Sharpe':>7s} {'maxDD%':>7s} {'turnover/day':>13s}")
        base_ret = None
        for name, fn in (("EW", ew), ("INVRISK", invrisk), ("INVTRAIL", invtrail), ("TIMED", timed)):
            x, tv = run(fn, rebal, cost)
            c_, v_, s_, dd_ = metrics(x)
            if name == "EW": base_ret = x
            ex = x - base_ret
            t = "" if name == "EW" else f"  excess vs EW {ex.mean() * 252 * 100:+.2f}%/yr (t {ex.mean() / ex.std() * np.sqrt(len(ex)):+.2f})"
            print(f"{name:10s} {c_:7.2f} {v_:6.1f} {s_:7.2f} {dd_:7.1f} {tv:13.3f}{t}")
