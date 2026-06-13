#!/usr/bin/env bash
# Run Maven against the treasury/ module inside a Maven+JDK21 container (host needs only Docker).
# --network host so the app can reach Postgres / the facilitator on localhost when running.
# Usage:  ./scripts/treasury-mvn.sh <maven goals...>
#   e.g.  ./scripts/treasury-mvn.sh -q test
#         ./scripts/treasury-mvn.sh spring-boot:run
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$HOME/.m2"

ENV_FILE_ARG=()
[[ -f "$ROOT/.env" ]] && ENV_FILE_ARG=(--env-file "$ROOT/.env")

exec docker run --rm --network host \
  "${ENV_FILE_ARG[@]}" \
  -v "$ROOT/treasury":/app -w /app \
  -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-21 \
  mvn "$@"
