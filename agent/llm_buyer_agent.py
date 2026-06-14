#!/usr/bin/env python3
"""
A dead-simple REAL LLM agent that spends through Agent Treasury.

An LLM is put in the decision seat: each turn it returns one JSON action ("pay this merchant this
much" / "finish"). The harness executes the payment via the treasury's POST /proxy and feeds the
result back, so the model reacts. The agent holds NO wallet and NO key — the treasury enforces rules.

Works with ANY OpenAI-compatible chat API (set LLM_BASE_URL / LLM_MODEL / LLM_API_KEY). Python stdlib
only. Logs every LLM call and every treasury call, with timestamps, flushed live.
"""
import datetime
import json
import os
import time
import urllib.error
import urllib.request

# ---- config (placeholders — fill these in) -------------------------------------------------------
LLM_API_KEY = os.environ.get("LLM_API_KEY", "")                       # REQUIRED — your free-tier key (set via env, never hardcode)
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "https://api.groq.com/openai/v1")  # any OpenAI-compatible API
LLM_MODEL = os.environ.get("LLM_MODEL", "llama-3.1-8b-instant")       # a model your provider offers

TREASURY = os.environ.get("TREASURY_URL", "http://localhost:8090")
API_KEY = os.environ.get("AGENT_KEY", "demo-key-agent-1")
USDC = "0x5425890298aed601595a70AB815c96711a31Bc65"

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


def log(msg):
    print(f"[{datetime.datetime.now():%H:%M:%S}] {msg}", flush=True)


def usd(atomic):
    return f"${atomic / 1e6:.2f}"


def chat(messages):
    payload = json.dumps({"model": LLM_MODEL, "messages": messages,
                          "temperature": 0.3, "max_tokens": 200}).encode()
    req = urllib.request.Request(
        f"{LLM_BASE_URL.rstrip('/')}/chat/completions", data=payload, method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {LLM_API_KEY}",
            # Providers like Groq sit behind Cloudflare, which 403s the default Python-urllib
            # User-Agent (error 1010). Send a normal browser-style UA so the request gets through.
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        })
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
    payload = {"payee": payee, "asset": USDC, "amountAtomic": amount_atomic}
    log(f"   → POST {TREASURY}/proxy  {json.dumps(payload)}")
    req = urllib.request.Request(
        f"{TREASURY}/proxy", data=json.dumps(payload).encode(), method="POST",
        headers={"Content-Type": "application/json", "X-Agent-Key": API_KEY})
    try:
        with urllib.request.urlopen(req) as r:
            status, body = r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        status, body = e.code, json.loads(e.read())
    except urllib.error.URLError as e:
        return 0, {"error": str(e.reason)}
    log(f"   ← HTTP {status}  state={body.get('state')} reason={body.get('denialReason')} "
        f"tx={(body.get('txHash') or '')[:16]}")
    return status, body


def main():
    if not LLM_API_KEY:
        log("⚠️  Set LLM_API_KEY (and optionally LLM_BASE_URL, LLM_MODEL). See agent/README.md.")
        return

    log(f"🧠 LLM agent starting — model={LLM_MODEL} via {LLM_BASE_URL}")
    log(f"   goal: buy market data within policy, no wallet/keys.  treasury={TREASURY}")
    messages = [{"role": "system", "content": SYSTEM},
                {"role": "user", "content": "Begin. Decide your first action."}]
    purchased = []

    for step in range(1, MAX_STEPS + 1):
        log("")
        log(f"[step {step}/{MAX_STEPS}] → calling LLM ({len(messages)} messages in context)…")
        t0 = time.time()
        try:
            content = chat(messages)
        except urllib.error.HTTPError as e:
            log(f"   ← LLM HTTP {e.code}: {e.read()[:200]}")
            break
        except Exception as e:  # noqa: BLE001 — keep the demo resilient
            log(f"   ← LLM call failed: {e}")
            break
        log(f"   ← LLM replied in {int((time.time() - t0) * 1000)}ms: {content.strip()[:240]}")
        messages.append({"role": "assistant", "content": content})

        action = extract_json(content)
        if not action:
            log("   (could not parse model output as JSON — stopping)")
            break

        log(f"🧠 decision: {action.get('thought', '').strip()}")
        if action.get("action") == "finish":
            log("🏁 agent decided it's done.")
            break

        mkey = action.get("merchant")
        amount = int(action.get("amountAtomic", 0))
        addr = MERCHANTS.get(mkey)
        if not addr:
            log(f"   (unknown merchant '{mkey}'; telling the model to use good/sketchy)")
            messages.append({"role": "user", "content": "Unknown merchant; use 'good' or 'sketchy'."})
            continue

        log(f"💸 paying {mkey} {usd(amount)} …")
        status, res = pay(addr, amount)
        state = res.get("state")
        if state == "SETTLED":
            log("   ✅ SETTLED")
            purchased.append((mkey, amount))
        elif state == "DENIED":
            log(f"   ⛔ DENIED — {res.get('denialReason')}")
        else:
            log(f"   ⚠️ unexpected: HTTP {status} {res}")

        messages.append({"role": "user",
                         "content": f"Treasury response: state={state} reason={res.get('denialReason')} "
                                    f"tx={res.get('txHash')}. Decide your next action (JSON only)."})
        time.sleep(0.5)

    total = sum(a for _, a in purchased)
    log("")
    log(f"── Done. Purchased {len(purchased)} dataset(s), total {usd(total)}. "
        f"The treasury enforced every limit. ──")


if __name__ == "__main__":
    main()
