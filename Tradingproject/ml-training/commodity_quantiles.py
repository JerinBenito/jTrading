"""Builds commodity_quantiles.json: volatility-scaled forward peak / dip / close / give-back quantiles per commodity, from ~26
years of daily futures history (data/comm/*.csv, downloaded by commodity_research.py's Yahoo step). The nightly report job
(commodity_report.py) only reads the JSON, so it never needs Yahoo (which blocks datacenter IPs). Re-run occasionally.

Scaled = log move / (EWMA daily vol x sqrt(H)).  Also validates the PER-COMMODITY approach walk-forward (expanding window,
re-fit each January, scored on the last 10 years) and stores the coverage so the app can show how well the bands held.
"""
import json, os
import numpy as np, pandas as pd
from scipy import stats

HERE = os.path.dirname(os.path.abspath(__file__))
FAMILIES = {"GOLD": "GC_F", "SILVER": "SI_F", "CRUDE": "CL_F", "NATGAS": "NG_F", "COPPER": "HG_F", "ALUMINIUM": "ALI_F"}
HS = [1, 2, 3, 5, 7, 10, 15, 21, 30, 42, 63]
PROBS = [0.1, 0.25, 0.5, 0.75, 0.9]
TRAIL = 21
BUCKETS = [("strong down", -99, -1.5), ("down", -1.5, -0.5), ("flat", -0.5, 0.5), ("up", 0.5, 1.5), ("strong up", 1.5, 99)]


def load(f):
    d = pd.read_csv(os.path.join(HERE, "data", "comm", f + ".csv"), parse_dates=["date"]).sort_values("date")
    d = d[(d.close > 0) & (d.high > 0) & (d.low > 0)]
    if f == "CL_F":
        d = d[~((d.date > "2020-03-25") & (d.date < "2020-05-20"))]
    d = d.reset_index(drop=True)
    d["ret"] = np.log(d.close).diff()
    d = d[d.ret.abs() < 0.35].reset_index(drop=True)
    d["ret"] = np.log(d.close).diff()
    d["sig"] = np.sqrt((d.ret ** 2).ewm(alpha=0.06, adjust=False).mean())
    d["trail_z"] = np.log(d.close / d.close.shift(TRAIL)) / (d.sig * np.sqrt(TRAIL))
    c, hi, lo = d.close.to_numpy(), d.high.to_numpy(), d.low.to_numpy(); n = len(d)
    for H in HS:
        pk = np.full(n, np.nan); tr = np.full(n, np.nan); fin = np.full(n, np.nan)
        for i in range(n - H):
            pk[i] = np.log(max(hi[i + 1:i + H + 1].max(), c[i]) / c[i]); tr[i] = np.log(min(lo[i + 1:i + H + 1].min(), c[i]) / c[i])
            fin[i] = np.log(c[i + H] / c[i])
        s = d.sig * np.sqrt(H)
        d[f"pk{H}"] = pk / s; d[f"tr{H}"] = tr / s; d[f"fin{H}"] = fin / s
    return d[(d.date >= "2001-01-01")].dropna(subset=["sig", "trail_z"]).reset_index(drop=True)


def q(x):
    return [round(float(v), 4) for v in np.quantile(x, PROBS)]


out = {"built": str(pd.Timestamp.now().date()), "horizons": HS, "probs": PROBS, "trailDays": TRAIL, "families": {}, "pooledByTrailingMove": {}, "validation": {}}
data = {fam: load(f) for fam, f in FAMILIES.items()}
for fam, d in data.items():
    out["families"][fam] = {"from": str(d.date.min().date()), "to": str(d.date.max().date()), "days": int(len(d)),
                            "peak": {str(H): q(d[f"pk{H}"].dropna()) for H in HS}, "dip": {str(H): q(-d[f"tr{H}"].dropna()) for H in HS},
                            "close": {str(H): q(d[f"fin{H}"].dropna()) for H in HS},
                            "giveback": {str(H): q((d[f"pk{H}"] - d[f"fin{H}"]).dropna()) for H in HS}}

# conditional on the trailing 21-day move (pooled across commodities, independent samples every H days)
pool = pd.concat([d.assign(fam=fam) for fam, d in data.items()], ignore_index=True)
for H in (10, 21, 42):
    rows = {}
    base = pool[pool[f"fin{H}"].notna()]
    for name, lo_, hi_ in BUCKETS:
        inb = (base.trail_z >= lo_) & (base.trail_z < hi_)
        g = pd.concat([x.iloc[::H] for _, x in base[inb].groupby("fam")])
        rest = pd.concat([x.iloc[::H] for _, x in base[~inb].groupby("fam")])
        f = g[f"fin{H}"]; fr = rest[f"fin{H}"]
        # the question is "is the month after such a move different from any other month?" - so test against the OTHER months,
        # not against zero (commodities drift up on average, which makes every bucket look significant against zero)
        tv = stats.ttest_ind(f, fr, equal_var=False).statistic
        rows[name] = {"n": int(len(g)), "meanClose": round(float(f.mean()), 3), "meanCloseOthers": round(float(fr.mean()), 3), "tVsOthers": round(float(tv), 2),
                      "pUp": round(float((f > 0).mean()), 3), "pUpOthers": round(float((fr > 0).mean()), 3), "peak": q(g[f"pk{H}"]), "dip": q(-g[f"tr{H}"])}
    out["pooledByTrailingMove"][str(H)] = rows

# walk-forward validation of the per-commodity quantile approach (expanding window, refit each January)
test_from = pool.date.max().year - 10
for H in (5, 21, 42):
    cov = {}
    for fam, d in data.items():
        hits = {"peak": [], "dip": []}
        for yr in range(max(test_from, d.date.min().year + 5), d.date.max().year + 1):
            tr = d[d.date < pd.Timestamp(yr, 1, 1) - pd.Timedelta(days=2 * H)]; te = d[d.date.dt.year == yr]
            for what, col, sg in (("peak", f"pk{H}", 1), ("dip", f"tr{H}", -1)):
                a = (sg * tr[col]).dropna(); b = (sg * te[col]).dropna()
                if len(a) > 500 and len(b):
                    hits[what] += list((b <= np.quantile(a, 0.9)).astype(int))
        cov[fam] = {k: round(100 * float(np.mean(v)), 1) for k, v in hits.items() if v}
    out["validation"][str(H)] = cov
    print(f"H={H:>2d}  q90 coverage out-of-sample, per-commodity own history:", cov)

json.dump(out, open(os.path.join(HERE, "commodity_quantiles.json"), "w"), indent=1)
print("written", os.path.getsize(os.path.join(HERE, "commodity_quantiles.json")), "bytes")
print("pooled strong-up -> next 21d:", out["pooledByTrailingMove"]["21"]["strong up"])
