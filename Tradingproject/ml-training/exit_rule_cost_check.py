"""Stricter re-test of the 'tight stop / wide target with zero signal' finding
(unconditional_barrier_backtest.py), fixing what flattered it:
  * gap fills: if a bar OPENS beyond the stop (or target) the fill is at the open, not at the stop price;
  * entry at day i's open and the entry day's own high/low count (the old loop skipped it);
  * same-bar stop+target = stop (conservative, as before);
  * round-trip costs swept 0 / 0.2 / 0.3 / 0.4 % (delivery STT 0.1% each side + charges + slippage ~ 0.3%);
  * baseline = plain buy-and-hold over the same hold period from the same entries, so the RULE's effect is
    separated from the basket's drift;
  * significance by ENTRY DATE: average trades across stocks per entry day, then a block bootstrap over
    dates (stocks move together), block = hold length; Bonferroni over all combinations.
"""
import sys, os
import numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(__file__))
from literature_checks import load_daily

COSTS = (0.0, 0.2, 0.3, 0.4)


def trade(o, h, l, c, i, pt, sl, hold):
    entry = o[i]
    tgt, stp = entry * (1 + pt / 100), entry * (1 - sl / 100)
    last = min(i + hold - 1, len(o) - 1)
    for j in range(i, last + 1):
        if j > i and o[j] <= stp: return (o[j] / entry - 1) * 100          # gapped through the stop
        if j > i and o[j] >= tgt: return (o[j] / entry - 1) * 100          # gapped through the target
        hit_s, hit_t = l[j] <= stp, h[j] >= tgt
        if hit_s: return -sl                                              # stop first when both hit
        if hit_t: return pt
    return (c[last] / entry - 1) * 100


def block_t(x, block, n=2000, seed=0):
    x = np.asarray(x, float); x = x[~np.isnan(x)]; k = len(x)
    rng = np.random.default_rng(seed); nb = int(np.ceil(k / block)); off = np.arange(block)[None, :]
    means = np.array([x[((rng.integers(0, k, nb)[:, None] + off) % k).ravel()[:k]].mean() for _ in range(n)])
    return x.mean(), x.mean() / means.std() if means.std() > 0 else np.nan, k


def main():
    d = load_daily()
    for col in ("open", "high", "low", "close"):
        d[col] = pd.to_numeric(d[col], errors="coerce")
    d = d.dropna(subset=["open", "high", "low", "close"]).sort_values(["instrument", "tradingDate"])
    by = {s: (g.tradingDate.to_numpy(), g.open.to_numpy(), g.high.to_numpy(), g.low.to_numpy(), g.close.to_numpy()) for s, g in d.groupby("instrument")}
    print(f"{len(by)} stocks, {d.tradingDate.min().date()}..{d.tradingDate.max().date()}")
    out = []
    combos = [(pt, sl, hold) for pt in (1.5, 2.0, 3.0, 5.0) for sl in (1.0, 1.5, 2.0) for hold in (3, 5, 10, 20)]
    for pt, sl, hold in combos:
        rule, bh = {}, {}
        for s, (dt, o, h, l, c) in by.items():
            for i in range(0, len(o) - hold, 3):
                rule.setdefault(dt[i], []).append(trade(o, h, l, c, i, pt, sl, hold))
                bh.setdefault(dt[i], []).append((c[i + hold - 1] / o[i] - 1) * 100)
        dates = sorted(rule)
        r = np.array([np.mean(rule[x]) for x in dates]); b = np.array([np.mean(bh[x]) for x in dates])
        row = dict(pt=pt, sl=sl, hold=hold, entries=len(dates), rule=r.mean(), buyhold=b.mean(), edge_vs_bh=(r - b).mean())
        for cst in COSTS:
            m, t, _ = block_t(r - cst, hold)
            row[f"net{cst}"] = m; row[f"t{cst}"] = t
        _, t_edge, _ = block_t(r - b, hold); row["t_edge"] = t_edge
        out.append(row)
    res = pd.DataFrame(out).sort_values("t0.3", ascending=False)
    pd.set_option("display.width", 220)
    show = ["pt", "sl", "hold", "entries", "rule", "buyhold", "edge_vs_bh", "t_edge", "net0.0", "t0.0", "net0.3", "t0.3"]
    print("\nTop 10 by t-stat AFTER a 0.3% round-trip cost (mean % per trade; averaged across stocks per entry date):")
    print(res[show].head(10).to_string(index=False, float_format=lambda x: f"{x:.3f}"))
    thr = 3.5  # |t| needed for Bonferroni over 48 combos at 5% two-sided is ~3.3
    print(f"\ncombos with positive mean after 0.0% cost: {(res['net0.0'] > 0).sum()} of {len(res)}  | t>3.3 (Bonferroni-ish): {(res['t0.0'] > 3.3).sum()}")
    for cst in COSTS[1:]:
        print(f"after {cst}% round-trip cost: positive mean in {(res[f'net{cst}'] > 0).sum()} of {len(res)} combos; t>3.3 in {(res[f't{cst}'] > 3.3).sum()}")
    print(f"\nrule vs buy-and-hold (same entries): mean edge {res['edge_vs_bh'].mean():+.3f}% per trade; combos where t_edge>3.3: {(res['t_edge'] > 3.3).sum()}")
    print(f"average buy-and-hold per trade across combos (the basket's drift over those holds): {res['buyhold'].mean():+.3f}%")


if __name__ == "__main__":
    main()
