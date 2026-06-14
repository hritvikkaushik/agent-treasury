#!/usr/bin/env bash
# Run the LLM-driven buyer agent. Treasury must be running (see docs/USAGE.md).
# Set your LLM creds first, e.g.:
#   export LLM_API_KEY=...           # required (free-tier key)
#   export LLM_BASE_URL=...          # optional, OpenAI-compatible endpoint
#   export LLM_MODEL=...             # optional, a model your provider offers
# Then: ./scripts/run-llm-agent.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${LLM_API_KEY:-}" ]]; then
  echo "LLM_API_KEY is not set. export it first (see agent/README.md)." >&2
  exit 1
fi

exec docker run --rm --network host \
  -e TREASURY_URL="${TREASURY_URL:-http://localhost:8090}" \
  -e AGENT_KEY="${AGENT_KEY:-demo-key-agent-1}" \
  -e LLM_API_KEY="$LLM_API_KEY" \
  -e LLM_BASE_URL="${LLM_BASE_URL:-https://api.groq.com/openai/v1}" \
  -e LLM_MODEL="${LLM_MODEL:-llama-3.1-8b-instant}" \
  -v "$ROOT/agent":/agent -w /agent \
  python:3.12-slim python llm_buyer_agent.py
