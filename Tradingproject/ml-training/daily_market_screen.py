"""Descriptive end-of-day screen of the NIFTY 50 basket from the app's own data - facts, not recommendations.

Everything is computed from /api/ml/features (daily OHLCV), /api/monitor/basket and /api/global-market/latest.
Nothing here has been shown to predict returns (see project_edge_search_results memory): relative strength,
volume expansion, RSI and moving-average position were all backtested on this basket with no usable edge.
Missing inputs are reported as DATA NOT AVAILABLE rather than guessed (no sector indices, no VWAP, no live news).
Usage: python daily_market_screen.py
"""
import json
import urllib.parse as p
import urllib.request as u
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd

B = "https://jerintradingsignal.duckdns.org"
g = lambda x: json.loads(u.urlopen(B + x, timeout=120).read())


def frame(sym):
    d = pd.DataFrame(g(f"/api/ml/features/{p.quote(sym)}"))
    for c in d.columns:
        if c not in ("instrument", "tradingDate"):
            d[c] = pd.to_numeric(d[c], errors="coerce")
    return d.sort_values("tradingDate").reset_index(drop=True)


def rsi14(c):
    d = c.diff()
    up, dn = d.clip(lower=0).ewm(alpha=1 / 14, adjust=False).mean(), (-d.clip(upper=0)).ewm(alpha=1 / 14, adjust=False).mean()
    return float(100 - 100 / (1 + up.iloc[-1] / dn.iloc[-1])) if dn.iloc[-1] > 0 else 100.0


def snapshot(sym, feed_close, today):
    """The stored daily row for TODAY is a mid-morning snapshot (taken by the morning run): close, high, low and
    volume are partial. So today's close comes from the final hourly candle (the live feed) and today's volume from
    the intraday volumeSoFarRatio; only COMPLETED days feed the levels and the volatility figures."""
    d = frame(sym)
    complete = d[d["tradingDate"] < today].reset_index(drop=True)
    t0, y = d.iloc[-1], complete.iloc[-1]
    live = g(f"/api/ml/intraday-features/{p.quote(sym)}/live") or {}
    close = float(feed_close)
    c = pd.concat([complete["close"], pd.Series([close])], ignore_index=True)
    ema = lambda n: c.ewm(span=n, adjust=False).mean().iloc[-1]
    hi20, lo20 = complete["high"].iloc[-20:].max(), complete["low"].iloc[-20:].min()
    return dict(
        symbol=sym, date=today, close=close, prev_close=y["close"], open=t0["open"],
        chg=(close / y["close"] - 1) * 100, gap=(t0["open"] / y["close"] - 1) * 100, open_to_close=(close / t0["open"] - 1) * 100,
        vol_ratio=live.get("volumeSoFarRatio"), rsi=rsi14(c), atr=y["atr14"], atr_pct=y["atr14"] / y["close"] * 100,
        r5=(close / complete["close"].iloc[-5] - 1) * 100, r20=(close / complete["close"].iloc[-20] - 1) * 100,
        ema9=ema(9), ema21=ema(21), ema50=ema(50), ema100=ema(100), ema200=ema(200),
        prev_high=y["high"], prev_low=y["low"], hi20=hi20, lo20=lo20,
    )


def main():
    now = datetime.now(timezone.utc) + timedelta(hours=5, minutes=30)
    basket = g("/api/monitor/basket")
    syms = [r["symbol"] for r in basket if r["symbol"] not in ("NIFTY", "BANKNIFTY")]
    today = now.date().isoformat()
    close_of = {r["symbol"]: r["lastClose"] for r in basket}
    stocks = pd.DataFrame([snapshot(s, close_of[s], today) for s in syms])
    idx = {s: snapshot(s, close_of[s], today) for s in ("NIFTY", "BANKNIFTY")}
    day = today
    print(f"Analysis timestamp: {now:%d-%m-%Y %H:%M} IST | data: today's final hourly candle (close 15:30 IST) + completed daily bars before today; source: the app's own backend "
          "(Upstox candles). Today's stored daily bar is a mid-morning snapshot and is NOT used. No tick or live-news feed")

    print("\n== MARKET CONTEXT ==")
    for s, v in idx.items():
        print(f"  {s:9s} close {v['close']:.1f}  day {v['chg']:+.2f}%  gap {v['gap']:+.2f}%  RSI {v['rsi']:.0f}  5d {v['r5']:+.2f}%  20d {v['r20']:+.2f}%  "
              f"vs EMA21 {100 * (v['close'] / v['ema21'] - 1):+.2f}%  vs EMA200 {100 * (v['close'] / v['ema200'] - 1):+.2f}%")
    adv = int((stocks["chg"] > 0).sum())
    print(f"  Breadth: {adv} advancers / {len(stocks) - adv} decliners; basket mean {stocks['chg'].mean():+.2f}%, median {stocks['chg'].median():.2f}%; "
          f"{int((stocks['close'] > stocks['ema21']).sum())}/50 above EMA21, {int((stocks['close'] > stocks['ema200']).sum())}/50 above EMA200")
    gm = {x["symbol"]: x for x in g("/api/global-market/latest")}
    print("  Global/macro (latest stored): " + "; ".join(
        f"{k} {v['price']:.2f} ({v['changePct']:+.2f}%)" for k, v in gm.items()))
    print("  DATA NOT AVAILABLE: sector indices, Asian markets, gold, FII/DII flows, live news/events, intraday VWAP")

    nm = idx["NIFTY"]["chg"]
    stocks["rs_nifty"] = stocks["chg"] - nm
    stocks["rs_5d"] = stocks["r5"] - idx["NIFTY"]["r5"]
    stocks["near_prev_high"] = 100 * (stocks["close"] / stocks["prev_high"] - 1)
    stocks["near_prev_low"] = 100 * (stocks["close"] / stocks["prev_low"] - 1)
    stocks["vs20hi"] = 100 * (stocks["close"] / stocks["hi20"] - 1)
    stocks["vs20lo"] = 100 * (stocks["close"] / stocks["lo20"] - 1)
    stocks["vs200"] = 100 * (stocks["close"] / stocks["ema200"] - 1)
    stocks["trend_stack"] = np.where((stocks.ema9 > stocks.ema21) & (stocks.ema21 > stocks.ema50), "up",
                                     np.where((stocks.ema9 < stocks.ema21) & (stocks.ema21 < stocks.ema50), "down", "mixed"))
    show = lambda title, df, cols: (print(f"\n== {title} =="), print(df[cols].round(2).to_string(index=False)))
    base = ["symbol", "close", "chg", "gap", "open_to_close", "rs_nifty", "vol_ratio", "rsi", "trend_stack"]
    show("Relative strength vs NIFTY today - top 6", stocks.sort_values("rs_nifty", ascending=False).head(6), base)
    show("Relative weakness vs NIFTY today - bottom 6", stocks.sort_values("rs_nifty").head(6), base)
    show("Volume today vs typical for the same point in the day (1.0 = normal) - top 6", stocks.sort_values("vol_ratio", ascending=False).head(6), base)
    show("Closed at/above the previous 20-day high (breakout candidates by price only)", stocks[stocks.close >= stocks.hi20].sort_values("vs20hi", ascending=False),
         ["symbol", "close", "hi20", "vs20hi", "vol_ratio", "rsi", "trend_stack"])
    show("Closed at/below the previous 20-day low (breakdown by price only)", stocks[stocks.close <= stocks.lo20].sort_values("vs20lo"),
         ["symbol", "close", "lo20", "vs20lo", "vol_ratio", "rsi", "trend_stack"])
    show("RSI extremes (<30 or >70)", stocks[(stocks.rsi < 30) | (stocks.rsi > 70)].sort_values("rsi"), ["symbol", "close", "rsi", "r5", "r20", "vs200", "trend_stack"])
    print(f"\nTrend-stack counts (EMA9>21>50 = up): {stocks['trend_stack'].value_counts().to_dict()}")
    print("\n== TYPICAL DAILY MOVE (ATR14 as % of price) - volatility, not direction: widest 6 / narrowest 6 ==")
    a = stocks.sort_values("atr_pct")
    print("  widest :", ", ".join(f"{r.symbol} {r.atr_pct:.1f}%" for r in a.tail(6).iloc[::-1].itertuples()))
    print("  narrowest:", ", ".join(f"{r.symbol} {r.atr_pct:.1f}%" for r in a.head(6).itertuples()))
    cal = g("/api/results-calendar/upcoming?days=7")
    print("\n== Scheduled quarterly results (vendor dates, may shift) ==")
    print("  " + ", ".join(f"{c['instrument']} {c['resultsDate']}" for c in cal))
    stocks.to_csv("daily_market_screen.csv", index=False)
    print("\n(full 50-stock table written to daily_market_screen.csv)")


if __name__ == "__main__":
    main()
