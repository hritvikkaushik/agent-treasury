#!/usr/bin/env bash
# Phase-0 smoke test: web3j EIP-3009 sign -> facilitator /verify -> /settle.
# Runs inside a Maven+JDK21 Docker container, so the host needs only Docker (no Java/Maven).
# Usage:  ./scripts/smoke.sh        (run from anywhere in the repo)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "No .env found. Copy .env.example to .env and fill it in." >&2
  exit 1
fi
set -a; source .env; set +a

FAC="${FACILITATOR_URL:-http://localhost:8080}"
if command -v curl >/dev/null 2>&1; then
  echo "Checking facilitator at $FAC ..."
  curl -fsS "$FAC/health" >/dev/null \
    && echo "facilitator healthy ✓" \
    || { echo "facilitator not reachable at $FAC — start it (infra/facilitator)." >&2; exit 1; }
fi

# --network host: reach the facilitator on localhost. --env-file: pass config into the container.
# Mount ~/.m2 to cache Maven deps between runs.
mkdir -p "$HOME/.m2"
exec docker run --rm --network host \
  --env-file "$ROOT/.env" \
  -v "$ROOT/smoke-test":/app -w /app \
  -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-21 \
  mvn -q compile exec:java
