"""Three published claims, checked on our own NSE data (Oct 2024 - Oct 2026):

T1  Intraday momentum (Gao, Han, Li, Zhou): the first-period return predicts the LATER return of the
    same day. Published for the S&P ETF; an Indian-market paper reports it for NIFTY. Tested at index
    level (NIFTY, BANKNIFTY), basket-average level, and stock level (cross-sectional).
T2  Short-term reversal is strongest when VIX is high (Nagel 2012, "Evaporating Liquidity"): does the
    5-day reversal in the 50 basket stocks pay more in high-VIX regimes? Ties to the VIX finding.
T3  Overnight vs intraday split: do stocks earn their return overnight (prev close -> open) rather
    than intraday, and do big gaps fade intraday?

Day is the independent unit throughout; stocks inside a day are correlated, so cross-sectional
statistics are averaged per day first and tested across days (block bootstrap where windows overlap).
"""
import json
import os
import tempfile
import urllib.request as u

import numpy as np
import pandas as pd
from scipy import stats

API = "https://jerintradingsignal.duckdns.org"
CACHE = os.environ.get("ABLATION_CACHE", os.path.join(tempfile.gettempdir(), "intraday_all_cache.json"))


def get_json(path, base=API, headers=None):
    req = u.Request(base + path, headers=headers or {})
    with u.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def boot_t(x, block=1, n=3000, seed=0):
    x = np.asarray(x, float)
    x = x[~np.isnan(x)]
    n_days = len(x)
    rng = np.random.default_rng(seed)
    nb = int(np.ceil(n_days / block))
    offs = np.arange(block)[None, :]
    means = np.empty(n)
    for i in range(n):
        idx = (rng.integers(0, n_days, nb)[:, None] + offs) % n_days
        means[i] = x[idx.ravel()[:n_days]].mean()
    se = means.std()
    return x.mean(), (x.mean() / se if se > 0 else float("nan")), n_days


def ols_t(x, y):
    """slope and HAC-ish t (Newey-West lag 5) for y = a + b x."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    ok = ~(np.isnan(x) | np.isnan(y))
    x, y = x[ok], y[ok]
    X = np.column_stack([np.ones(len(x)), x])
    beta = np.linalg.lstsq(X, y, rcond=None)[0]
    e = y - X @ beta
    xe = X * e[:, None]
    S = xe.T @ xe
    L = 5
    for l in range(1, L + 1):
        w = 1 - l / (L + 1)
        G = xe[l:].T @ xe[:-l]
        S += w * (G + G.T)
    XtXi = np.linalg.inv(X.T @ X)
    V = XtXi @ S @ XtXi
    r2 = 1 - e.var() / y.var()
    return beta[1], beta[1] / np.sqrt(V[1, 1]), r2, len(x)


# ------------------------------------------------------------------ T1
def t1_intraday_momentum():
    print("\n================ T1: first-hour return -> later return the same day ================")
    df = pd.DataFrame(json.load(open(CACHE, encoding="utf-8")))
    for c in ("returnSoFarPct", "remainingDriftPct", "volumeSoFarRatio"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    keep = df[["instrument", "tradingDate", "hoursSinceOpen", "returnSoFarPct", "remainingDriftPct", "volumeSoFarRatio"]]
    first = keep[keep.hoursSinceOpen == 0].set_index(["instrument", "tradingDate"])
    wide = pd.DataFrame({"r_first": first["returnSoFarPct"], "vol0": first["volumeSoFarRatio"]})
    # remainingDriftPct at hour h = move from the end of candle h to the close
    wide["rest_after_1h"] = keep[keep.hoursSinceOpen == 0].set_index(["instrument", "tradingDate"])["remainingDriftPct"]
    wide["last_75m"] = keep[keep.hoursSinceOpen == 4].set_index(["instrument", "tradingDate"])["remainingDriftPct"]
    wide["last_15m"] = keep[keep.hoursSinceOpen == 5].set_index(["instrument", "tradingDate"])["remainingDriftPct"]
    wide = wide.reset_index()
    targets = [("rest_after_1h", "rest of day after first hour"), ("last_75m", "last 75 minutes"), ("last_15m", "last 15 minutes")]

    print("-- index level (the published setup) --")
    for idx in ("NIFTY", "BANKNIFTY"):
        w = wide[wide.instrument == idx]
        for col, lab in targets:
            b, t, r2, n = ols_t(w["r_first"], w[col])
            print(f"  {idx:9s} first-hour -> {lab:30s} slope={b:+.4f} t(NW)={t:+.2f} R2={100 * r2:.2f}% n={n}")
    stocks = wide[~wide.instrument.isin(["NIFTY", "BANKNIFTY"])]
    ew = stocks.groupby("tradingDate")[["r_first", "rest_after_1h", "last_75m", "last_15m", "vol0"]].mean()
    print("-- basket average (50 stocks, equal weight) --")
    for col, lab in targets:
        b, t, r2, n = ols_t(ew["r_first"], ew[col])
        print(f"  basket    first-hour -> {lab:30s} slope={b:+.4f} t(NW)={t:+.2f} R2={100 * r2:.2f}% n={n}")
    hi = ew["vol0"] >= ew["vol0"].median()
    print("-- basket, high-volume days vs low-volume days (the paper: stronger on high-volume days) --")
    for lab_d, mask in (("high-vol days", hi), ("low-vol days", ~hi)):
        b, t, r2, n = ols_t(ew.loc[mask, "r_first"], ew.loc[mask, "rest_after_1h"])
        print(f"  {lab_d:14s} first-hour -> rest of day: slope={b:+.4f} t(NW)={t:+.2f} n={n}")
    print("-- single stocks, cross-sectional (does a stock's own first-hour move predict its own later move?) --")
    for col, lab in targets:
        ics = stocks.groupby("tradingDate").apply(
            lambda g: stats.spearmanr(g["r_first"], g[col], nan_policy="omit")[0] if g[col].nunique() > 5 else np.nan)
        mean, t, n = boot_t(ics.to_numpy())
        print(f"  mean daily Rank IC, first-hour -> {lab:30s} {mean:+.4f}  t={t:+.2f}  ({n} days)")


# ------------------------------------------------------------------ T2 / T3
def load_daily():
    syms = [r["symbol"] for r in get_json("/api/monitor/basket") if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
    fr = []
    for s in syms:
        d = pd.DataFrame(get_json(f"/api/ml/features/{s}"))
        d["instrument"] = s
        fr.append(d)
    df = pd.concat(fr, ignore_index=True)
    df["tradingDate"] = pd.to_datetime(df["tradingDate"])
    for c in ("open", "close", "gapFromPrevClosePct", "dailyReturnPct", "return5dPct", "forwardReturn5dPct"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df.sort_values(["instrument", "tradingDate"])


def load_vix():
    d = get_json("/v8/finance/chart/%5EINDIAVIX?range=2y&interval=1d", base="https://query1.finance.yahoo.com",
                 headers={"User-Agent": "Mozilla/5.0"})["chart"]["result"][0]
    v = pd.DataFrame({"tradingDate": pd.to_datetime(d["timestamp"], unit="s").tz_localize(None).normalize(),
                      "vix": d["indicators"]["quote"][0]["close"]}).dropna()
    return v.drop_duplicates("tradingDate").set_index("tradingDate")["vix"]


def t2_reversal_vix(df):
    print("\n================ T2: does 5-day reversal pay more when VIX is high? (Nagel 2012) ================")
    vix = load_vix()
    d = df.dropna(subset=["return5dPct", "forwardReturn5dPct"])
    rows = []
    for day, g in d.groupby("tradingDate"):
        if len(g) < 30:
            continue
        ic = stats.spearmanr(g["return5dPct"], g["forwardReturn5dPct"])[0]
        k = max(3, len(g) // 10)
        s = g.sort_values("return5dPct")
        spread = s.head(k)["forwardReturn5dPct"].mean() - s.tail(k)["forwardReturn5dPct"].mean()  # buy losers, sell winners
        rows.append((day, ic, spread))
    r = pd.DataFrame(rows, columns=["tradingDate", "ic", "spread"]).set_index("tradingDate").join(vix, how="inner")
    r["vix_prev_med"] = r["vix"].rolling(60, min_periods=30).median().shift(1)   # no look-ahead
    r = r.dropna()
    r["high_vix"] = r["vix"] > r["vix_prev_med"]
    print(f"  {len(r)} days; high-VIX days (above own trailing-60d median): {int(r['high_vix'].sum())}")
    print(f"  Spearman(VIX level, daily reversal spread) = {stats.spearmanr(r['vix'], r['spread'])[0]:+.3f}  "
          f"(positive = reversal pays more when VIX is high)")
    for lab, mask in (("HIGH VIX", r["high_vix"]), ("LOW VIX ", ~r["high_vix"])):
        m_ic, t_ic, _ = boot_t(r.loc[mask, "ic"].to_numpy(), block=5)
        m_sp, t_sp, n = boot_t(r.loc[mask, "spread"].to_numpy(), block=5)
        print(f"  {lab}: n={n:3d}  mean Rank IC {m_ic:+.4f} (t={t_ic:+.2f})   buy-losers/sell-winners 5d spread {m_sp:+.3f}% (t={t_sp:+.2f})")
    diff = r.loc[r.high_vix, "spread"].mean() - r.loc[~r.high_vix, "spread"].mean()
    rng = np.random.default_rng(1)
    hi, lo = r.loc[r.high_vix, "spread"].to_numpy(), r.loc[~r.high_vix, "spread"].to_numpy()
    boots = []
    for _ in range(3000):
        # block-resample each regime's days (blocks of 5)
        def rs(x):
            nb = int(np.ceil(len(x) / 5))
            idx = (rng.integers(0, len(x), nb)[:, None] + np.arange(5)[None, :]) % len(x)
            return x[idx.ravel()[:len(x)]].mean()
        boots.append(rs(hi) - rs(lo))
    print(f"  high-minus-low VIX spread difference = {diff:+.3f}%   block-bootstrap 95% CI "
          f"{np.percentile(boots, 2.5):+.3f}..{np.percentile(boots, 97.5):+.3f}")


def t3_overnight(df):
    print("\n================ T3: overnight (prev close -> open) vs intraday (open -> close) ================")
    d = df.dropna(subset=["open", "close", "gapFromPrevClosePct"]).copy()
    d["intraday"] = (d["close"] / d["open"] - 1) * 100
    d["overnight"] = d["gapFromPrevClosePct"]
    day = d.groupby("tradingDate")[["overnight", "intraday"]].mean()
    for col in ("overnight", "intraday"):
        m, t, n = boot_t(day[col].to_numpy())
        print(f"  basket avg {col:9s} per day: {m:+.4f}%  (t={t:+.2f}, {n} days)  -> about {m * 250:+.1f}% per year")
    ics = d.groupby("tradingDate").apply(lambda g: stats.spearmanr(g["overnight"], g["intraday"])[0] if len(g) > 30 else np.nan)
    m, t, n = boot_t(ics.to_numpy())
    print(f"  cross-sectional: does a bigger overnight gap predict a weaker intraday move? mean daily Rank IC {m:+.4f} (t={t:+.2f})")
    for lab, mask in (("gap UP   > +1%", d.overnight > 1), ("gap DOWN < -1%", d.overnight < -1)):
        g = d[mask]
        dm, dt, dn = boot_t(g.groupby("tradingDate")["intraday"].mean().to_numpy())
        print(f"  {lab}: {len(g):5d} stock-days, mean intraday move after the gap {g['intraday'].mean():+.3f}%  "
              f"(day-level t={dt:+.2f}, {dn} days)")


if __name__ == "__main__":
    t1_intraday_momentum()
    daily = load_daily()
    t2_reversal_vix(daily)
    t3_overnight(daily)
