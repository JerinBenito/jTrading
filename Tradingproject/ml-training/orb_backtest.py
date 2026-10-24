"""Opening Range Breakout (ORB) on the NIFTY 50 basket with 1-minute candles (Jan 2022 -> now): does it make money
after realistic costs? Literature: Zarattini-Barbon-Aziz found ORB works on US 'stocks in play' (high relative volume at
the open). This tests the same idea on your data, honestly.

Rules (one trade per stock per day, no look-ahead):
  * opening range = high/low of the first N minutes (N in 5, 15, 30)
  * after the range, the first minute whose high breaks above the range high = LONG, whose low breaks below = SHORT
  * fill at the breakout level, or at that minute's open if it gapped through; plus slippage SLIP% against us
  * stop = opposite side of the opening range (gap-through fills at the open); otherwise exit at the 15:15 close
  * 'stocks in play' = top K by RELATIVE opening-range volume (this stock's OR volume / its own previous 14-day mean)
Costs: round-trip fees+slippage COST% (0.10% is a realistic retail intraday figure on liquid large caps). Stocks on the
same day are correlated, so significance uses the DAY as the unit (mean of that day's trades, then a t-stat over days).
Multiple looks: 3 OR lengths x 2 universes = 6 configs -> Bonferroni threshold |t| ~ 2.6.
Usage: python orb_backtest.py
"""
import numpy as np
import pandas as pd
from scipy import stats

import minute_store as ms

SLIP = 0.02          # % adverse on entry and on a stop fill
COSTS = (0.06, 0.10, 0.20)
TOP_K = 10
OR_LENS = (5, 15, 30)
EOD_IDX = 360        # 15:15 candle (09:15 + 360 min)


def stock_trades(sym):
    d = ms.load_minute(sym)
    if d.empty or sym in ("NIFTY", "BANKNIFTY"):
        return []
    out = []
    ordv = {}
    for day, g in d.groupby("day"):
        if len(g) != 375:
            continue
        o, h, l, c, v = (g[k].to_numpy(float) for k in ("open", "high", "low", "close", "volume"))
        ordv[day] = (o, h, l, c, v)
    days = sorted(ordv)
    for n in OR_LENS:
        hist = []
        for day in days:
            o, h, l, c, v = ordv[day]
            or_hi, or_lo, or_vol = h[:n].max(), l[:n].min(), v[:n].sum()
            relvol = or_vol / np.mean(hist[-14:]) if len(hist) >= 14 and np.mean(hist[-14:]) > 0 else np.nan
            hist.append(or_vol)
            if or_hi <= or_lo or not np.isfinite(relvol):
                continue
            up = np.where(h[n:EOD_IDX] > or_hi)[0]
            dn = np.where(l[n:EOD_IDX] < or_lo)[0]
            iu, idn = (up[0] + n if len(up) else 10**9), (dn[0] + n if len(dn) else 10**9)
            if iu == idn == 10**9 or iu == idn:
                continue
            side = 1 if iu < idn else -1
            i = iu if side == 1 else idn
            level = or_hi if side == 1 else or_lo
            entry = (max(level, o[i]) if side == 1 else min(level, o[i])) * (1 + side * SLIP / 100)
            stop = or_lo if side == 1 else or_hi
            ex = None
            for j in range(i, EOD_IDX + 1):
                if side == 1 and l[j] <= stop:
                    ex = min(stop, o[j] if j > i else stop) * (1 - SLIP / 100); break
                if side == -1 and h[j] >= stop:
                    ex = max(stop, o[j] if j > i else stop) * (1 + SLIP / 100); break
            if ex is None:
                ex = c[EOD_IDX]
            out.append(dict(sym=sym, day=day, n=n, side=side, relvol=relvol, ret=side * (ex / entry - 1) * 100,
                            stopped=ex != c[EOD_IDX], risk_pct=abs(entry - stop) / entry * 100))
    return out


def day_series(t, cost):
    return (t.assign(net=t.ret - cost).groupby("day")["net"].mean())


def tstat(x):
    x = np.asarray(x, float)
    return x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 and x.std(ddof=1) > 0 else np.nan


if __name__ == "__main__":
    syms = [s for s in ms.symbols() if s not in ("NIFTY", "BANKNIFTY")]
    rows = []
    for k, s in enumerate(syms, 1):
        rows += stock_trades(s)
        if k % 10 == 0:
            print(f"  processed {k}/{len(syms)} stocks, {len(rows):,} trades", flush=True)
    t = pd.DataFrame(rows)
    t["year"] = pd.to_datetime(t["day"]).dt.year
    print(f"\n{len(t):,} breakout trades across {t.sym.nunique()} stocks, {t.day.nunique()} days "
          f"({t.day.min()}..{t.day.max()})\n")
    print(f"{'config':26s} {'trades':>7s} {'gross%':>8s} {'net@0.06':>9s} {'net@0.10':>9s} {'net@0.20':>9s} {'win%':>6s} {'stopped%':>9s} {'day t@0.10':>11s}")
    configs = []
    for n in OR_LENS:
        for uni in ("all", f"top{TOP_K} rel-volume"):
            x = t[t.n == n]
            if uni != "all":
                x = x[x.groupby("day")["relvol"].rank(ascending=False, method="first") <= TOP_K]
            configs.append((f"OR{n}min, {uni}", x))
            ds = day_series(x, 0.10)
            print(f"{'OR' + str(n) + 'min, ' + uni:26s} {len(x):7d} {x.ret.mean():+8.3f} {x.ret.mean() - 0.06:+9.3f} {x.ret.mean() - 0.10:+9.3f} {x.ret.mean() - 0.20:+9.3f} "
                  f"{100 * (x.ret > 0).mean():6.1f} {100 * x.stopped.mean():9.1f} {tstat(ds):+11.2f}")
    print("\nBonferroni (6 configs): a config is only interesting if net@0.10 > 0 AND |t| >= ~2.6.")
    best = max(configs, key=lambda kv: tstat(day_series(kv[1], 0.10)))
    print(f"\nBest-looking config by t-stat: {best[0]}")
    x = best[1]
    for yr, g in x.groupby("year"):
        print(f"   {yr}: trades {len(g):5d}  gross {g.ret.mean():+.3f}%  net@0.10 {g.ret.mean() - 0.10:+.3f}%  win {100 * (g.ret > 0).mean():.1f}%  day t {tstat(day_series(g, 0.10)):+.2f}")
    for side, lab in ((1, "long only"), (-1, "short only")):
        g = x[x.side == side]
        print(f"   {lab:11s}: trades {len(g):5d}  gross {g.ret.mean():+.3f}%  net@0.10 {g.ret.mean() - 0.10:+.3f}%  day t {tstat(day_series(g, 0.10)):+.2f}")
