#!/usr/bin/env bash
# Clear the payment feed for a fresh demo/rehearsal — WITHOUT removing the demo agent.
#
# Do NOT truncate the `agents` table while the app is running: DataSeeder only seeds the demo agent at
# startup, so wiping it mid-run breaks auth (every payment 401s) until you restart the app.
# This script clears only payments + ledger, keeping the agent. On-chain reputation is unaffected.
set -euo pipefail

docker exec treasury-pg psql -U treasury -d treasury -c \
  "truncate payment_intent, journal_entry cascade;" >/dev/null \
  && echo "payment feed + ledger cleared (demo agent kept)."
