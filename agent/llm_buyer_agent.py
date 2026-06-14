#!/usr/bin/env python3
"""
A dead-simple REAL LLM agent that spends through Agent Treasury.

An LLM is put in the decision seat: it's told its goal + the available providers + its policy, and on
each turn it returns one JSON action ("pay this merchant this much" or "finish"). The harness executes
the payment via the treasury's POST /proxy and feeds the result back to the model, which reacts. The
agent holds NO wallet and NO key — the treasury enforces every rule.

Works with ANY OpenAI-compatible chat API (set LLM_BASE_URL / LLM_MODEL / LLM_API_KEY). Python stdlib
only — no pip installs. See agent/README.md for free-tier options.
"""
import json
import os
import time
import urllib.error
import urllib.request

# ---- config (placeholders — fill these in) -------------------------------------------------------
LLM_API_KEY = os.environ.get("LLM_API_KEY", "")                       # REQUIRED — your free-tier key
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "https://api.groq.com/openai/v1")  # any OpenAI-compatible API
LLM_MODEL = os.environ.get("LLM_MODEL", "llama-3.1-8b-instant")       # a model your provider offers

TREASURY = os.environ.get("TREASURY_URL", "http://localhost:8090")
API_KEY = os.environ.get("AGENT_KEY", "demo-key-agent-1")
USDC = "0x5425890298aed601595a70AB815c96711a31Bc65"

# The model refers to merchants by short key; the harness maps to the real on-chain address.
MERCHANTS = {
    "good": "0x6f409644a8a0b598284e8ca1a7562759f2189fbf",      # good-data-co (reputable)
    "sketchy": "0x000000000000000000000000000000000000dEaD",   # sketchy-data-inc (low reputation)
}

SYSTEM = """You are an autonomous procurement agent buying market data. You pay providers through a \
"treasury" that enforces spending rules — you have NO wallet of your own.

Providers (use the short key as "merchant"):
- "good"    = good-data-co, a reputable data vendor.
- "sketchy" = sketchy-data-inc, cheap but unknown / low reputation.

Policy (enforced by the treasury, not you): amounts are atomic USDC (1000000 = $1.00); per-payment cap
about $0.50; daily budget about $5.00; trusted counterparties only.

Your task: evaluate the providers and acquire data. To do the job properly you MUST attempt all of:
1) a normal ~$0.10 purchase from "good",
2) a ~$0.10 purchase from "sketchy",
3) a large premium ~$0.60 purchase from "good".
Learn from each treasury response. Do not repeat a payment the treasury just refused. Finish once \
you've attempted those.

Reply with ONLY one JSON object, no prose and no code fences:
{"thought":"<one short sentence>","action":"pay","merchant":"good"|"sketchy","amountAtomic":<integer>}
or
{"thought":"<one short sentence>","action":"finish"}"""

MAX_STEPS = 8


def usd(atomic):
    return f"${atomic / 1e6:.2f}"


def chat(messages):
    payload = json.dumps({"model": LLM_MODEL, "messages": messages,
                          "temperature": 0.3, "max_tokens": 200}).encode()
    req = urllib.request.Request(
        f"{LLM_BASE_URL.rstrip('/')}/chat/completions", data=payload, method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {LLM_API_KEY}"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read())
    return data["choices"][0]["message"]["content"]


def extract_json(text):
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end == -1:
        return None
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None


def pay(payee, amount_atomic):
    body = json.dumps({"payee": payee, "asset": USDC, "amountAtomic": amount_atomic}).encode()
    req = urllib.request.Request(
        f"{TREASURY}/proxy", data=body, method="POST",
        headers={"Content-Type": "application/json", "X-Agent-Key": API_KEY})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())
    except urllib.error.URLError as e:
        return 0, {"error": str(e.reason)}


def main():
    if not LLM_API_KEY:
        print("⚠️  Set LLM_API_KEY (and optionally LLM_BASE_URL, LLM_MODEL). See agent/README.md.")
        return

    print(f"🧠  LLM agent ({LLM_MODEL}) — goal: buy market data within policy, no wallet/keys.\n")
    messages = [{"role": "system", "content": SYSTEM},
                {"role": "user", "content": "Begin. Decide your first action."}]
    purchased = []

    for _ in range(MAX_STEPS):
        try:
            content = chat(messages)
        except Exception as e:  # noqa: BLE001 — keep the demo resilient to provider hiccups
            print(f"LLM call failed: {e}")
            break
        messages.append({"role": "assistant", "content": content})

        action = extract_json(content)
        if not action:
            print(f"(could not parse model output, stopping)\n{content}")
            break

        print(f"🧠 {action.get('thought', '').strip()}")
        if action.get("action") == "finish":
            print("🏁 agent decided it's done.")
            break

        mkey = action.get("merchant")
        amount = int(action.get("amountAtomic", 0))
        addr = MERCHANTS.get(mkey)
        if not addr:
            messages.append({"role": "user", "content": "Unknown merchant; use 'good' or 'sketchy'."})
            continue

        print(f"💸 paying {mkey} {usd(amount)} …")
        status, res = pay(addr, amount)
        state, reason, tx = res.get("state"), res.get("denialReason"), res.get("txHash")
        if state == "SETTLED":
            print(f"   ✅ SETTLED ({(tx or '')[:14]}…)\n")
            purchased.append((mkey, amount))
        elif state == "DENIED":
            print(f"   ⛔ DENIED — {reason}\n")
        else:
            print(f"   ⚠️  {status}: {res}\n")

        messages.append({"role": "user",
                         "content": f"Treasury response: state={state} reason={reason} tx={tx}. "
                                    f"Decide your next action (JSON only)."})
        time.sleep(0.5)

    total = sum(a for _, a in purchased)
    print(f"\n── Done. Purchased {len(purchased)} dataset(s), total {usd(total)}. "
          f"The treasury enforced every limit. ──")


if __name__ == "__main__":
    main()
