"""Downloads the pooled intraday feature+outcome export (NIFTY, BANKNIFTY, NIFTY 50 basket)
used by train_intraday_model.py. Re-run this any time to pull fresh data as more trading days
accumulate on the server."""
import json
import os
import urllib.request

API_BASE = "https://jerintradingsignal.duckdns.org"
OUT_PATH = os.path.join(os.path.dirname(__file__), "data", "intraday_features.json")

if __name__ == "__main__":
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    print(f"Downloading {API_BASE}/api/ml/intraday-features/all ...")
    with urllib.request.urlopen(f"{API_BASE}/api/ml/intraday-features/all", timeout=300) as resp:
        rows = json.loads(resp.read())
    with open(OUT_PATH, "w") as f:
        json.dump(rows, f)
    print(f"{len(rows)} rows written to {OUT_PATH}")
