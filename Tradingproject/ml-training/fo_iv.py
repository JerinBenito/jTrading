"""Implied volatility per stock-day from NSE's free F&O bhavcopy (fo_bhavcopy_fetch.py): Black-Scholes inversion on OTM options
near the money, interpolated to a constant 30-calendar-day maturity.

  spot S      : the stock's cash close from the app's own daily history (the old bhavcopy format has no underlying price)
  forward F   : S * exp(r*T), r = 6.5% p.a. (India T-bill ballpark), dividends ignored (a small, slow-moving bias)
  options     : settlement price > 0.5 and open interest > 0, |ln(K/F)| <= 12%, OTM side only (calls above F, puts below F)
  ATM IV(E)   : mean IV of the call just above F and the put just below F for each expiry E
  iv30        : sqrt of total variance interpolated to T = 30/365 between the two expiries bracketing 30 days
                (nearest-expiry IV if only one expiry is within 12-60 days)
Output: data/fo/iv_daily.csv  (date, sym, iv30, iv_near, T_near). Usage: python fo_iv.py
"""
import glob, json, os, urllib.request as u
import numpy as np, pandas as pd
from scipy.special import ndtr

B = "https://jerintradingsignal.duckdns.org"
HERE = os.path.dirname(os.path.abspath(__file__))
R = 0.065


def bs_price(S, K, T, sig, is_call):
    d1 = (np.log(S / K) + (R + 0.5 * sig ** 2) * T) / (sig * np.sqrt(T)); d2 = d1 - sig * np.sqrt(T)
    call = S * ndtr(d1) - K * np.exp(-R * T) * ndtr(d2)
    put = K * np.exp(-R * T) * ndtr(-d2) - S * ndtr(-d1)
    return np.where(is_call, call, put)


def implied_vol(price, S, K, T, is_call, iters=60):
    lo, hi = np.full_like(price, 0.01), np.full_like(price, 3.0)
    for _ in range(iters):
        mid = 0.5 * (lo + hi)
        p = bs_price(S, K, T, mid, is_call)
        too_high = p > price
        hi = np.where(too_high, mid, hi); lo = np.where(too_high, lo, mid)
    iv = 0.5 * (lo + hi)
    intrinsic = np.where(is_call, np.maximum(S - K * np.exp(-R * T), 0), np.maximum(K * np.exp(-R * T) - S, 0))
    ok = (price > intrinsic + 0.05) & (iv > 0.015) & (iv < 2.9)
    return np.where(ok, iv, np.nan)


def atm_points(d, spot_for, basket, alias=None):
    """ATM implied vol per (date, sym, expiry) from raw bhavcopy rows `d` (schema of fo_bhavcopy_fetch.parse).
    spot_for(sym, date_str) -> the stock's cash close that day (NaN if unknown)."""
    d = d[d["tp"] == "OPT"].copy()
    d["sym"] = d["sym"].replace(alias or {})
    d = d[d["sym"].isin(basket)]
    d["S"] = [spot_for(s, dt) for s, dt in zip(d["sym"], d["date"])]
    d = d.dropna(subset=["S"])
    d["T"] = (pd.to_datetime(d["expiry"]) - pd.to_datetime(d["date"])).dt.days.clip(lower=1) / 365.0
    d["F"] = d["S"] * np.exp(R * d["T"])
    d = d[(np.abs(np.log(d["strike"] / d["F"])) <= 0.12) & (d["settle"] > 0.5) & (d["oi"] > 0)]
    d["is_call"] = d["opt"] == "CE"
    d = d[(d["is_call"] & (d["strike"] >= d["F"])) | (~d["is_call"] & (d["strike"] < d["F"]))]       # OTM only
    d["iv"] = implied_vol(d["settle"].to_numpy(float), d["S"].to_numpy(float), d["strike"].to_numpy(float), d["T"].to_numpy(float), d["is_call"].to_numpy())
    d = d.dropna(subset=["iv"])
    # ATM IV per (date, sym, expiry): nearest OTM call above and put below the forward
    d["dist"] = np.abs(np.log(d["strike"] / d["F"]))
    near = d.sort_values("dist").groupby(["date", "sym", "expiry", "is_call"]).head(1)
    atm = near.groupby(["date", "sym", "expiry"]).agg(iv=("iv", "mean"), n=("iv", "size"), T=("T", "first")).reset_index()
    return len(d), atm[atm["n"] == 2]


def iv30_table(atm):
    """Constant-30-day implied vol per (date, sym) from the per-expiry ATM points."""
    out = []
    for (date, sym), g in atm.groupby(["date", "sym"]):
        g = g[(g["T"] >= 5 / 365) & (g["T"] <= 70 / 365)].sort_values("T")
        if g.empty:
            continue
        T30 = 30 / 365
        below, above = g[g["T"] <= T30], g[g["T"] > T30]
        if len(below) and len(above):
            a, b = below.iloc[-1], above.iloc[0]
            w = (T30 - a["T"]) / (b["T"] - a["T"])
            var = (1 - w) * a["iv"] ** 2 * a["T"] + w * b["iv"] ** 2 * b["T"]
            iv30 = np.sqrt(var / T30)
        elif len(g[(g["T"] >= 12 / 365) & (g["T"] <= 60 / 365)]):
            iv30 = g[(g["T"] >= 12 / 365) & (g["T"] <= 60 / 365)].iloc[0]["iv"]
        else:
            continue
        out.append((date, sym, iv30 * 100, g.iloc[0]["iv"] * 100, g.iloc[0]["T"] * 365))
    return pd.DataFrame(out, columns=["date", "sym", "iv30", "iv_near", "T_near"])


if __name__ == "__main__":
    basket = [r["symbol"] for r in json.loads(u.urlopen(B + "/api/monitor/basket", timeout=60).read()) if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
    spot = {}
    for s in basket:
        rows = json.loads(u.urlopen(f"{B}/api/ml/features/{s}", timeout=120).read())
        spot[s] = pd.Series({r["tradingDate"]: float(r["close"]) for r in rows})
    alias = {"TATAMOTORS": "TMPV"}
    frames = []
    for f in sorted(glob.glob(os.path.join(HERE, "data", "fo", "????-??.csv.gz"))):
        n_opts, atm_f = atm_points(pd.read_csv(f, low_memory=False), lambda s, dt: spot[s].get(dt, np.nan), basket, alias)
        frames.append(atm_f)
        print(f"{os.path.basename(f)}: {n_opts:,} usable options -> {len(atm_f)} (stock, expiry) ATM points", flush=True)
    res = iv30_table(pd.concat(frames, ignore_index=True))
    out_path = os.environ.get("IV_OUT", os.path.join(HERE, "data", "fo", "iv_daily.csv"))
    res.to_csv(out_path, index=False)
    print(f"\n{len(res):,} stock-day IV observations, {res.sym.nunique()} stocks, {res.date.min()}..{res.date.max()}")
    print("iv30 (annualised %) quantiles:", res.iv30.quantile([.05, .5, .95]).round(1).to_dict())
