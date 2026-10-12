"""Evidence dossier per stock - descriptive statistics from the app's own history, not a forecast or a recommendation.

For each symbol: today's facts (final hourly candle), the price levels that exist (prior day, 20-day, 52-week, EMAs),
typical move sizes, and what THIS stock did historically after comparable days, with the sample size always shown
(small n = anecdote). Only completed daily bars feed the history. Usage: python stock_dossier.py SYM [SYM ...]
"""
import json
import sys
import urllib.parse as p
import urllib.request as u

import numpy as np
import pandas as pd

B = "https://jerintradingsignal.duckdns.org"
g = lambda x: json.loads(u.urlopen(B + x, timeout=120).read())


def load(sym):
    d = pd.DataFrame(g(f"/api/ml/features/{p.quote(sym)}"))
    for c in d.columns:
        if c not in ("instrument", "tradingDate"):
            d[c] = pd.to_numeric(d[c], errors="coerce")
    return d.sort_values("tradingDate").reset_index(drop=True)


def rsi14(c):
    dl = c.diff()
    up, dn = dl.clip(lower=0).ewm(alpha=1 / 14, adjust=False).mean(), (-dl.clip(upper=0)).ewm(alpha=1 / 14, adjust=False).mean()
    return (100 - 100 / (1 + up / dn)).iloc[-1]


def pct(a, q):
    return float(np.percentile(a, q)) if len(a) else float("nan")


def dossier(sym, today, feed_close, nifty_chg, results, vol_ratio):
    d = load(sym)
    d = d[d["tradingDate"] < today].reset_index(drop=True)          # completed days only
    y = d.iloc[-1]
    close = float(feed_close)
    c = pd.concat([d["close"], pd.Series([close])], ignore_index=True)
    chg = (close / y["close"] - 1) * 100
    nxt = (d["close"].shift(-1) / d["close"] - 1) * 100               # next-day close-to-close
    nxt5 = (d["close"].shift(-5) / d["close"] - 1) * 100
    rng = (d["high"] - d["low"]) / d["close"] * 100
    ema = lambda n: c.ewm(span=n, adjust=False).mean().iloc[-1]
    lv = {"prev-day high": y["high"], "prev-day low": y["low"], "20d high": d["high"].iloc[-20:].max(), "20d low": d["low"].iloc[-20:].min(),
          "52w high": d["high"].iloc[-252:].max(), "52w low": d["low"].iloc[-252:].min(), "EMA21": ema(21), "EMA50": ema(50), "EMA200": ema(200)}
    print(f"\n{'=' * 78}\n{sym}  close {close:.2f}  day {chg:+.2f}% (NIFTY {nifty_chg:+.2f}%, relative {chg - nifty_chg:+.2f}%)  "
          f"RSI {rsi14(c):.0f}  volume vs typical {vol_ratio if vol_ratio is None else round(vol_ratio, 2)}x"
          + (f"  | RESULTS {results}" if results else ""))
    print("  levels (distance from close): " + "; ".join(f"{k} {v:.1f} ({100 * (v / close - 1):+.1f}%)" for k, v in sorted(lv.items(), key=lambda kv: kv[1], reverse=True)))
    print(f"  typical size: ATR14 {y['atr14'] / y['close'] * 100:.2f}% of price | median day range {rng.median():.2f}% | |close-to-close| median {d['dailyReturnPct'].abs().median():.2f}%, 90th pct {pct(d['dailyReturnPct'].abs(), 90):.2f}%")
    print(f"  next-day close-to-close, ALL {len(d)} days: 5th {pct(nxt.dropna(), 5):+.2f}%  median {pct(nxt.dropna(), 50):+.2f}%  95th {pct(nxt.dropna(), 95):+.2f}%  up {100 * (nxt.dropna() > 0).mean():.0f}% of days")
    # comparable days
    same = (d["dailyReturnPct"] >= 0.8 * chg) & (d["dailyReturnPct"] <= 1.4 * chg) if chg > 0 else (d["dailyReturnPct"] <= 0.8 * chg) & (d["dailyReturnPct"] >= 1.4 * chg)
    idx = same[same].index[same[same].index < len(d) - 5]
    if len(idx) >= 3:
        a = nxt.loc[idx].dropna(); a5 = nxt5.loc[idx].dropna()
        print(f"  after days like today ({chg:+.1f}% +-30%): n={len(idx)}  next day mean {a.mean():+.2f}% median {a.median():+.2f}% up {100 * (a > 0).mean():.0f}% | next 5d mean {a5.mean():+.2f}% up {100 * (a5 > 0).mean():.0f}%"
              + ("   [small sample - anecdote]" if len(idx) < 15 else ""))
    else:
        print(f"  after days like today: only {len(idx)} comparable days - no usable history")
    rs = d["close"].pipe(lambda s: pd.Series([rsi14(s.iloc[: i + 1]) if i >= 20 else np.nan for i in range(len(s))]))
    if rsi14(c) < 32:
        o = rs[(rs < 32)].index; o = o[o < len(d) - 5]
        if len(o) >= 3:
            a = nxt5.loc[o].dropna()
            print(f"  history when RSI<32: n={len(o)} days; next 5d mean {a.mean():+.2f}% median {a.median():+.2f}% up {100 * (a > 0).mean():.0f}%  (clustered: consecutive days are the same episode)")
    return dict(symbol=sym, close=close)


def main():
    syms = sys.argv[1:]
    basket = {r["symbol"]: r for r in g("/api/monitor/basket")}
    today = pd.Timestamp.now(tz="Asia/Kolkata").date().isoformat()
    nifty = load("NIFTY"); nifty = nifty[nifty["tradingDate"] < today]
    nchg = (basket["NIFTY"]["lastClose"] / nifty.iloc[-1]["close"] - 1) * 100
    cal = {e["instrument"]: e["resultsDate"] for e in g("/api/results-calendar/upcoming?days=10")}
    for s in syms:
        live = g(f"/api/ml/intraday-features/{p.quote(s)}/live") or {}
        dossier(s, today, basket[s]["lastClose"], nchg, cal.get(s), live.get("volumeSoFarRatio"))


if __name__ == "__main__":
    main()
