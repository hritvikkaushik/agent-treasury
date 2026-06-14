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

## The LLM-driven agent (`llm_buyer_agent.py`)

Same idea, but a real LLM is in the decision seat. Each turn the model returns one JSON action
(`pay` a merchant / `finish`); the harness calls the treasury and feeds the result back, so the model
reacts to denials. It's primed to attempt all the guardrail cases (trusted pay, untrusted block,
over-cap block). Python stdlib only.

### Pick any OpenAI-compatible provider (free tiers work)
Set three env vars (placeholders are filled with a sensible default):

| Var | What | Example |
|-----|------|---------|
| `LLM_API_KEY` | **required** — your key | `gsk_…` / `sk-…` |
| `LLM_BASE_URL` | OpenAI-compatible endpoint | `https://api.groq.com/openai/v1` (Groq, free) |
| `LLM_MODEL` | a model your provider offers | `llama-3.1-8b-instant` |

Other free-tier options (all OpenAI-compatible): Groq, OpenRouter (`https://openrouter.ai/api/v1`),
Google Gemini (`https://generativelanguage.googleapis.com/v1beta/openai`), or a local server
(Ollama/LM Studio). It's a tiny agent (~6 short calls), so a free tier is plenty.

### Run
```bash
export LLM_API_KEY=...           # your free-tier key
# export LLM_BASE_URL=...        # optional override
# export LLM_MODEL=...           # optional override
./scripts/run-llm-agent.sh
```
The treasury must be up first. Watch the dashboard react as the model decides what to pay.

> Note: the scripted `buyer_agent.py` is verified end-to-end; the LLM agent's harness is verified, but
> you supply the model/key, so behavior depends on your provider. If a weak model returns malformed
> JSON, lower the shopping ambition or use the scripted agent as the reliable fallback.
