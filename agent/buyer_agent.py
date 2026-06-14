#!/usr/bin/env python3
"""
Autonomous research agent that buys market data through Agent Treasury.

It pursues a goal — assemble a market report by purchasing datasets — but it holds NO wallet and NO
private key. It simply asks the treasury to pay providers; the treasury enforces budget + reputation
guardrails and signs/settles on-chain. The agent reacts to each outcome on its own (skips untrusted
providers, stops when a limit is hit). No human in the loop.

This is a plain HTTP client (Python stdlib only) — the treasury doesn't care what language the agent
is written in. Swap this decision loop for an LLM and you have an AI agent (see agent/README.md).
"""
import json
import os
import time
import urllib.error
import urllib.request
import uuid

TREASURY = os.environ.get("TREASURY_URL", "http://localhost:8090")
API_KEY = os.environ.get("AGENT_KEY", "demo-key-agent-1")
USDC = "0x5425890298aed601595a70AB815c96711a31Bc65"

# The agent's shopping list: data providers it would like to buy from, in priority order.
# (Providers are identified by their on-chain address; reputation lives on-chain in ERC-8004.)
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


def usd(atomic):
    return f"${atomic / 1e6:.2f}"


def pay(payee, amount_atomic):
    """Ask the treasury to pay a provider. Returns (http_status, body_dict)."""
    body = json.dumps({"payee": payee, "asset": USDC, "amountAtomic": amount_atomic}).encode()
    req = urllib.request.Request(
        f"{TREASURY}/proxy",
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Agent-Key": API_KEY,
            "Idempotency-Key": str(uuid.uuid4()),
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())
    except urllib.error.URLError as e:
        return 0, {"error": f"cannot reach treasury at {TREASURY}: {e.reason}"}


def main():
    print("🤖  Research Agent")
    print("    goal: assemble a market report by purchasing data — within budget, trusted providers only.")
    print(f"    (no wallet, no keys — paying via the treasury at {TREASURY})\n")
    time.sleep(1)

    bought, skipped = [], []
    for label, addr, price in PLAN:
        print(f"→ I need: {label}.  Requesting payment of {usd(price)}…")
        time.sleep(1.5)
        status, res = pay(addr, price)
        state = res.get("state")

        if state == "SETTLED":
            tx = (res.get("txHash") or "")[:16]
            print(f"   ✅ paid ({tx}…) — dataset acquired.\n")
            bought.append(label)
        elif state == "DENIED":
            reason = res.get("denialReason")
            print(f"   ⛔ treasury blocked it: {reason} — {res.get('denialDetail', '')}")
            if reason == "REPUTATION_BELOW_THRESHOLD":
                print("      → provider isn't trustworthy enough. Skipping it.\n")
                skipped.append(label)
            elif reason == "PER_TX_CAP_EXCEEDED":
                print("      → too pricey for a single purchase. Skipping.\n")
                skipped.append(label)
            elif reason == "DAILY_BUDGET_EXHAUSTED":
                print("      → daily budget spent. Wrapping up the report with what I have.\n")
                break
            else:
                skipped.append(label)
        elif status == 401:
            print("   ⚠️  treasury rejected my API key. Is AGENT_KEY correct?\n")
            break
        else:
            print(f"   ⚠️  unexpected response ({status}): {res}\n")
        time.sleep(1)

    print("── Report assembled ──")
    print(f"   purchased ({len(bought)}): " + (", ".join(bought) or "nothing"))
    print(f"   skipped   ({len(skipped)}): " + (", ".join(skipped) or "nothing"))
    print("\n   The agent stayed within policy the whole time — the treasury made sure of it.")


if __name__ == "__main__":
    main()
