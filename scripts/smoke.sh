#!/usr/bin/env bash
# Phase-0 smoke test: loads .env and runs the web3j EIP-3009 -> facilitator verify/settle harness.
# Usage:  ./scripts/smoke.sh        (run from anywhere in the repo)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "No .env found. Copy .env.example to .env and fill it in." >&2
  exit 1
fi

set -a; source .env; set +a

echo "Checking facilitator at ${FACILITATOR_URL:-http://localhost:8080} ..."
curl -fsS "${FACILITATOR_URL:-http://localhost:8080}/health" >/dev/null \
  && echo "facilitator healthy ✓" \
  || { echo "facilitator not reachable — is it running? (infra/facilitator)" >&2; exit 1; }

cd smoke-test
exec mvn -q compile exec:java
