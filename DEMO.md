# Demo — Agent Treasury

A ~3-minute live demo: an autonomous agent pays real USDC over **x402** on Avalanche Fuji, gated by
on-chain **ERC-8004** reputation, with budget/velocity guardrails — no human in the loop. Watch a
payment settle, a low-reputation merchant get blocked, a budget cap bite, and a merchant's reputation
rise as it's paid.

## Prerequisites (one-time, already done on the dev box)
- Funded wallets: treasury `0x44bbaa…` (test USDC + AVAX), facilitator `0x6f40…` (AVAX).
- ERC-8004 registries deployed to Fuji (addresses in `.env`); merchants seeded
  (good-data-co ≈ rep 85, sketchy-data-inc ≈ rep 12).
- `.env` filled (see `.env.example`).

## Start the stack (Linux box)
```bash
cd ~/Documents/agent-treasury
./scripts/dev-db.sh up                      # Postgres (+ treasury_test)
# facilitator (verify/settle on Fuji):
docker start x402-facilitator 2>/dev/null || docker run -d --name x402-facilitator \
  --restart unless-stopped -v "$PWD/infra/facilitator/config.json:/app/config.json:ro" \
  --env-file .env -p 8080:8080 ghcr.io/x402-rs/x402-facilitator
# treasury app, REAL chain mode:
docker run -d --name treasury-app --network host --env-file .env \
  -e X402_ENABLED=true -e ERC8004_ENABLED=true \
  -v "$PWD/treasury":/app -w /app -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -q spring-boot:run
```
Open the dashboard: **http://localhost:8090/**

Convenience for the script below:
```bash
USDC=0x5425890298aed601595a70AB815c96711a31Bc65
GOOD=0x6f409644a8a0b598284e8ca1a7562759f2189fbf
SKETCHY=0x000000000000000000000000000000000000dEaD
pay() { curl -s -X POST localhost:8090/proxy -H "X-Agent-Key: demo-key-agent-1" \
  -H "Content-Type: application/json" -d "{\"payee\":\"$1\",\"asset\":\"$USDC\",\"amountAtomic\":$2}"; echo; }
```

## The script

**0. Setup (on screen).** Show the dashboard: one agent (Research Agent, $5.00 daily budget, min
reputation 60) and two merchants registered on-chain — good-data-co (rep 85), sketchy-data-inc (rep 12).

**1. Happy path — a real payment.**
```bash
pay $GOOD 100000      # 0.10 USDC
```
→ `SETTLED` with a real tx hash. Click it on the dashboard → Snowtrace shows the on-chain USDC
transfer. Point out: the agent never held a key; the treasury signed; settlement was gasless for the
payer. The agent's budget bar ticks up.

**2. The block that matters — reputation.**
```bash
pay $SKETCHY 100000
```
→ `402 DENIED — REPUTATION_BELOW_THRESHOLD`. The dashboard row flashes red with the reason. **No
on-chain call happened** — the treasury read sketchy-data-inc's on-chain reputation (12 < 60) and
refused before signing. The system protected the money.

**3. Budget guardrail.**
```bash
pay $GOOD 600000      # 0.60 > 0.50 per-tx cap
```
→ `402 DENIED — PER_TX_CAP_EXCEEDED`. (Or loop smaller payments to exhaust the $5 daily budget and
show `DAILY_BUDGET_EXHAUSTED` with the burn-down bar maxed.)

**4. Reputation is earned — the loop closes.**
Read good-data-co's reputation, pay it a couple times, read again:
```bash
docker run --rm --env-file .env -v "$PWD/contracts/erc8004":/app -w /app -v "$HOME/.npm":/root/.npm \
  node:20 bash -lc "npm install --no-audit --no-fund >/dev/null && npx hardhat run scripts/read.js --network fuji"
pay $GOOD 50000 ; sleep 8 ; pay $GOOD 50000 ; sleep 12
# re-run the read.js line above
```
→ good-data-co's on-chain reputation rises (e.g. 85 → 90): each successful payment makes the treasury
write `giveFeedback` to ERC-8004. Reputation is built by real transaction history.

**Close:** "Real value moved on Avalanche, gated by on-chain identity and reputation — and no way for
the agent to move value it shouldn't. That's the missing trust-and-control layer for agent commerce."

## Reset between rehearsals
Clear the payment feed but **keep the demo agent** (the agent is only seeded at app startup, so do
NOT truncate the `agents` table while the app is running — that breaks auth until you restart):
```bash
./scripts/demo-reset.sh      # truncates payment_intent + journal_entry only
```
(On-chain reputation persists across resets — to re-show the 85→90 rise from a clean base, redeploy
the registries via `contracts/erc8004/scripts/deploy.js`. If you ever DO wipe the `agents` table,
just restart the app: `docker restart treasury-app` re-seeds the demo agent.)

## Insurance
- Pre-fund wallets the night before (faucets: build.avax.network console for AVAX, faucet.circle.com
  for USDC). Each real run spends ~0.0003 AVAX gas + the USDC amount.
- Record a screen capture of a clean run as a fallback (public RPC / faucet flakiness is the usual
  live-demo killer).
- Offline fallback: omit `X402_ENABLED`/`ERC8004_ENABLED` — the app runs with stubs (instant fake
  settlement, in-memory reputation), so the guardrail narrative still demos without the chain.
