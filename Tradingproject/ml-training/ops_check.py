"""One-command 'is everything alive right now?' check for the trading backend. Read-only (the live-feed test only
subscribes to NIFTY). Best run during market hours (09:15-15:30 IST, Mon-Fri) because that is when ticks, new hourly
candles and AI calls exist; outside market hours the live-feed test can only confirm the connection, not ticks.

    python ops_check.py
"""
import asyncio
import json
import time
import urllib.request as u
from collections import Counter
from datetime import datetime, timedelta, timezone

B = "https://jerintradingsignal.duckdns.org"
now = datetime.now(timezone.utc) + timedelta(hours=5, minutes=30)
market_open = now.weekday() < 5 and (9, 15) <= (now.hour, now.minute) <= (15, 30)
print(f"IST {now:%a %d %b %H:%M} - market {'OPEN' if market_open else 'closed'}\n")


def get(path, timeout=60):
    return json.loads(u.urlopen(B + path, timeout=timeout).read())


def line(ok, text):
    print(f"  [{'OK ' if ok else 'BAD'}] {text}")


# 1. backend
try:
    line(get("/actuator/health").get("status") == "UP", "backend health")
except Exception as e:
    line(False, f"backend unreachable: {e!r}")
    raise SystemExit(1)

# 2. candles
basket = get("/api/monitor/basket")
last = Counter(r["lastCandleTs"][:10] for r in basket)
today = now.date().isoformat()
fresh = last.get(today, 0)
line(fresh == len(basket) or not market_open, f"instruments with a candle dated today: {fresh}/{len(basket)} (latest dates seen: {dict(last)})")
nifty = next(r for r in basket if r["symbol"] == "NIFTY")
age_h = (datetime.now(timezone.utc) - datetime.fromisoformat(nifty["lastCandleTs"].replace("Z", "+00:00"))).total_seconds() / 3600
line(age_h < 3 or not market_open, f"NIFTY latest candle {nifty['lastCandleTs']} ({age_h:.1f} h old)")

# 3. live feed (ticks only arrive while the market is open)
async def feed():
    import websockets
    t = time.time()
    async with websockets.connect("wss://jerintradingsignal.duckdns.org/ws/live-feed", open_timeout=20) as ws:
        await ws.send(json.dumps({"action": "subscribe", "instrument": "NIFTY"}))
        msgs = []
        while time.time() - t < 20 and len(msgs) < 3:
            try:
                msgs.append(await asyncio.wait_for(ws.recv(), timeout=20 - (time.time() - t)))
            except asyncio.TimeoutError:
                break
        return msgs

try:
    msgs = asyncio.run(feed())
    err = [m for m in msgs if "error" in m.lower() or "isn't logged in" in m.lower()]
    if err:
        line(False, f"live feed error: {err[0][:150]}")
    elif msgs:
        line(True, f"live feed ticks received: {len(msgs)} (e.g. {msgs[0][:90]})")
    else:
        line(not market_open, "live feed connected but no ticks" + (" (expected: market closed)" if not market_open else " - MARKET IS OPEN, ticks should be flowing"))
except Exception as e:
    line(False, f"live feed test failed: {e!r}")

# 4. order-book snapshots today
try:
    depth = get("/api/market-depth/recent")
    n = sum(1 for d in depth if d["ts"][:10] == today)
    line(n > 0 or not market_open, f"order-book snapshots dated today: {n}")
except Exception as e:
    line(False, f"order-book check failed: {e!r}")

# 5. AI calls today
try:
    snaps = get("/api/ai-predictions/TCS/snapshots?horizon=INTRADAY&days=2")
    calls = [s for s in snaps if s["targetDate"] == today and not s["afterClose"]]
    line(bool(calls) or not market_open, f"TCS same-day AI calls today: {len(calls)}" + (f" (first at {calls[0]['predictedAtTs'][11:16]} UTC)" if calls else ""))
except Exception as e:
    line(False, f"AI ledger check failed: {e!r}")

# 6. GitHub workflows (public API)
try:
    runs = json.loads(u.urlopen(u.Request("https://api.github.com/repos/JerinBenito/jTrading/actions/runs?per_page=30", headers={"User-Agent": "ops"}), timeout=30).read())["workflow_runs"]
    todays = [r for r in runs if r["created_at"][:10] == datetime.now(timezone.utc).date().isoformat()]
    c = Counter((r["name"], r["conclusion"] or r["status"]) for r in todays)
    line(not any(k[1] == "failure" for k in c), "today's GitHub runs: " + (", ".join(f"{n}: {s} x{v}" for (n, s), v in sorted(c.items())) or "none yet"))
except Exception as e:
    line(True, f"(GitHub runs not checked: {e!r})")
