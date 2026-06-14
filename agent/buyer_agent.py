#!/usr/bin/env python3
"""
Autonomous research agent that buys market data through Agent Treasury.

It pursues a goal — assemble a market report by purchasing datasets — but it holds NO wallet and NO
private key. It asks the treasury to pay providers; the treasury enforces budget + reputation
guardrails and signs/settles on-chain. The agent reacts to each outcome on its own.

Logs every step (with timestamps) and every treasury request/response, flushed live.
"""
import datetime
import json
import os
import time
import urllib.error
import urllib.request
import uuid

TREASURY = os.environ.get("TREASURY_URL", "http://localhost:8090")
API_KEY = os.environ.get("AGENT_KEY", "demo-key-agent-1")
USDC = "0x5425890298aed601595a70AB815c96711a31Bc65"

GOOD = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf"      # good-data-co  (reputable)
SKETCHY = "0x000000000000000000000000000000000000dEaD"   # sketchy-data-inc (low reputation)

PLAN = [
    ("premium market feed   (good-data-co)", GOOD, 100_000),     # 0.10 -> settles
    ("cheap sentiment data  (sketchy-data-inc)", SKETCHY, 100_000),  # blocked: reputation
    ("historical prices     (good-data-co)", GOOD, 100_000),     # 0.10 -> settles
    ("whale-alert stream    (good-data-co)", GOOD, 100_000),     # 0.10 -> settles
    ("full firehose dump    (good-data-co)", GOOD, 600_000),     # 0.60 -> blocked: per-tx cap
    ("on-chain flow metrics (good-data-co)", GOOD, 100_000),     # 0.10 -> settles
]


def log(msg):
    print(f"[{datetime.datetime.now():%H:%M:%S}] {msg}", flush=True)


def usd(atomic):
    return f"${atomic / 1e6:.2f}"


def pay(payee, amount_atomic):
    """Ask the treasury to pay a provider. Returns (http_status, body_dict). Logs request + response."""
    payload = {"payee": payee, "asset": USDC, "amountAtomic": amount_atomic}
    log(f"   → POST {TREASURY}/proxy  {json.dumps(payload)}")
    req = urllib.request.Request(
        f"{TREASURY}/proxy", data=json.dumps(payload).encode(), method="POST",
        headers={"Content-Type": "application/json", "X-Agent-Key": API_KEY,
                 "Idempotency-Key": str(uuid.uuid4())})
    try:
        with urllib.request.urlopen(req) as r:
            status, body = r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        status, body = e.code, json.loads(e.read())
    except urllib.error.URLError as e:
        log(f"   ← ERROR cannot reach treasury: {e.reason}")
        return 0, {"error": str(e.reason)}
    log(f"   ← HTTP {status}  state={body.get('state')} reason={body.get('denialReason')} "
        f"tx={(body.get('txHash') or '')[:16]}")
    return status, body


def main():
    log("🤖 Research Agent starting")
    log(f"   goal: assemble a market report by buying data — within budget, trusted providers only")
    log(f"   treasury={TREASURY}  (no wallet, no keys)")
    bought, skipped = [], []

    for i, (label, addr, price) in enumerate(PLAN, 1):
        log("")
        log(f"[{i}/{len(PLAN)}] I need: {label} — will request {usd(price)}.")
        time.sleep(1.2)
        status, res = pay(addr, price)
        state = res.get("state")

        if state == "SETTLED":
            log(f"   ✅ paid — dataset acquired.")
            bought.append(label)
        elif state == "DENIED":
            reason = res.get("denialReason")
            log(f"   ⛔ blocked: {reason} — {res.get('denialDetail', '')}")
            if reason == "DAILY_BUDGET_EXHAUSTED":
                log("      → daily budget spent; wrapping up the report.")
                break
            log("      → skipping and moving on.")
            skipped.append(label)
        elif status == 401:
            log("   ⚠️ treasury rejected my API key (AGENT_KEY). Stopping.")
            break
        else:
            log(f"   ⚠️ unexpected response: {res}")
        time.sleep(0.8)

    log("")
    log("── Report assembled ──")
    log(f"   purchased ({len(bought)}): " + (", ".join(bought) or "nothing"))
    log(f"   skipped   ({len(skipped)}): " + (", ".join(skipped) or "nothing"))
    log("   The agent stayed within policy the whole time — the treasury made sure of it.")


if __name__ == "__main__":
    main()
