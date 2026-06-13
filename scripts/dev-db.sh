#!/usr/bin/env bash
# Manage the local Postgres dev/test database (container "treasury-pg").
# Usage:  ./scripts/dev-db.sh [up|down]
set -euo pipefail

NAME=treasury-pg

case "${1:-up}" in
  up)
    if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
      docker start "$NAME" >/dev/null && echo "started existing $NAME"
    else
      docker run -d --name "$NAME" \
        -e POSTGRES_DB=treasury \
        -e POSTGRES_USER=treasury \
        -e POSTGRES_PASSWORD=treasury \
        -p 5432:5432 \
        postgres:16 >/dev/null && echo "created $NAME"
    fi
    for _ in $(seq 1 30); do
      if docker exec "$NAME" pg_isready -U treasury >/dev/null 2>&1; then
        echo "postgres ready on localhost:5432 (db=treasury user=treasury)"
        exit 0
      fi
      sleep 1
    done
    echo "postgres did not become ready in time" >&2
    exit 1
    ;;
  down)
    docker rm -f "$NAME" >/dev/null 2>&1 && echo "removed $NAME" || echo "$NAME not running"
    ;;
  *)
    echo "usage: $0 [up|down]" >&2
    exit 1
    ;;
esac
