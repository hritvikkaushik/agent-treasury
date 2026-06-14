# Autonomous buyer agent

A standalone program that plays the role of an AI agent: it pursues a goal (assemble a market report)
by buying data from providers **through the treasury**, and reacts to the guardrails on its own. It
holds no wallet and no keys — it just calls `POST /proxy`. Demonstrates "an agent moving value, no
human in the loop."

## Run
The treasury must be running first (see [`../docs/USAGE.md`](../docs/USAGE.md)). Then, from the repo root:
```bash
./scripts/run-agent.sh
```
Watch the dashboard (`http://localhost:8090/`) light up as the agent works: green rows for purchases,
red rows for blocked ones (untrusted provider, over-cap). Works in offline or real-chain mode — the
agent behaves identically; only the treasury's settlement differs.

## What it does
Walks a shopping list of providers and, for each, asks the treasury to pay. It interprets the outcome:
- **SETTLED** → dataset acquired.
- **DENIED `REPUTATION_BELOW_THRESHOLD`** → provider not trustworthy → skip.
- **DENIED `PER_TX_CAP_EXCEEDED`** → too expensive → skip.
- **DENIED `DAILY_BUDGET_EXHAUSTED`** → stop and finish the report.

Config via env: `TREASURY_URL` (default `http://localhost:8090`), `AGENT_KEY` (default
`demo-key-agent-1`).

## Making it a real LLM agent (optional upgrade)
The decision loop is plain Python. To make it genuinely AI-driven, replace the loop with an LLM that's
given the goal and a single tool — `pay(payee, amountAtomic)` — wired to the same `POST /proxy` call.
The model decides which providers to buy from and how to react to denials; the `pay` function (and the
treasury behind it) stays exactly the same. Needs an LLM API key. Ask and this can be added.
