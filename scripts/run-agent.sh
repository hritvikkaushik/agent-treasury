#!/usr/bin/env bash
# Run the autonomous buyer agent against a running treasury (host needs only Docker).
# The treasury must already be up (see docs/USAGE.md). Usage: ./scripts/run-agent.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

exec docker run --rm --network host \
  -e TREASURY_URL="${TREASURY_URL:-http://localhost:8090}" \
  -e AGENT_KEY="${AGENT_KEY:-demo-key-agent-1}" \
  -e PYTHONUNBUFFERED=1 \
  -v "$ROOT/agent":/agent -w /agent \
  python:3.12-slim python -u buyer_agent.py
