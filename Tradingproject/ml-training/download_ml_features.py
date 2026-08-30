"""Downloads the multi-day ML feature/label dataset (ml_feature_snapshots) for every live
instrument, used by train_multiday_model.py and validate_multiday_walkforward.py. Fetches the
current instrument list from /api/monitor/basket (NIFTY + BANKNIFTY + resolved NIFTY 50 basket
symbols) rather than hardcoding it, so it stays correct if the basket ever changes."""
import json
import os
import urllib.parse
import urllib.request

API_BASE = "https://jerintradingsignal.duckdns.org"
OUT_PATH = os.path.join(os.path.dirname(__file__), "data", "ml_features_all.json")

if __name__ == "__main__":
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)

    with urllib.request.urlopen(f"{API_BASE}/api/monitor/basket", timeout=30) as resp:
        basket = json.loads(resp.read())
    symbols = [row["symbol"] for row in basket]
    print(f"{len(symbols)} instruments: {','.join(symbols)}")

    all_rows = []
    for sym in symbols:
        url = f"{API_BASE}/api/ml/features/{urllib.parse.quote(sym)}"
        with urllib.request.urlopen(url, timeout=30) as resp:
            rows = json.loads(resp.read())
        all_rows.extend(rows)
        print(f"{sym}: {len(rows)} rows")

    with open(OUT_PATH, "w") as f:
        json.dump(all_rows, f)
    print(f"\nTotal: {len(all_rows)} rows written to {OUT_PATH}")
