# Usage — Agent Treasury

Operational reference: prerequisites, configuration, build/test/run, deploy, and the HTTP API. For an
agent developer's perspective see [USER-GUIDE](./USER-GUIDE.md); to run the demo see [`../DEMO.md`](../DEMO.md).

## 1. Prerequisites

| Tool | Version | For |
|------|---------|-----|
| Docker | recent | facilitator, Postgres, and containerized builds |
| Java + Maven | 21 / 3.9 | only if building on the host instead of via Docker |
| Node | 20+ | only if running the ERC-8004 deploy on the host |
| git | any | — |

The runtime box needs **only Docker** — builds run in containers (`scripts/treasury-mvn.sh`).
A funded Fuji wallet is needed for on-chain mode (test AVAX + USDC).

## 2. Configuration (`.env`, see `.env.example`)

Consumed via `docker --env-file` — **no quotes, no inline comments** (a space-containing value like
`USDC_DOMAIN_NAME=USD Coin` must stay unquoted; never `source` the file).

| Var | Purpose | Default |
|-----|---------|---------|
| `RPC_URL` | Fuji RPC | `https://api.avax-test.network/ext/bc/C/rpc` |
| `CHAIN_ID` | chain id | `43113` |
| `TREASURY_PRIVATE_KEY` | omnibus wallet (signs payments + feedback) | — |
| `FACILITATOR_PRIVATE_KEY` | facilitator signer (pays gas) | — |
| `USDC_ADDRESS` | Fuji USDC | `0x5425890298aed601595a70AB815c96711a31Bc65` |
| `USDC_DOMAIN_NAME` / `_VERSION` | EIP-712 domain (verified on-chain) | `USD Coin` / `2` |
| `NETWORK` | x402 v1 network string | `avalanche-fuji` |
| `FACILITATOR_URL` | facilitator base URL | `http://localhost:8080` |
| `IDENTITY_REGISTRY_ADDRESS` / `REPUTATION_REGISTRY_ADDRESS` | ERC-8004 (from deploy) | — |
| `X402_ENABLED` | real settlement vs stub | `false` |
| `ERC8004_ENABLED` | real reputation/feedback vs stub | `false` |
| `RECONCILIATION_INITIAL_DELAY_MS` / `_INTERVAL_MS` | scheduler | `30000` / `60000` |

Feature flags: leave both unset for an **offline** run (stubs); set `X402_ENABLED=true
ERC8004_ENABLED=true` for a **real on-chain** run.

## 3. Build & test (containerized)

```bash
./scripts/dev-db.sh up                 # Postgres treasury-pg (+ treasury_test for tests)
./scripts/treasury-mvn.sh -q test      # 38 tests, offline (uses treasury_test)
./scripts/treasury-mvn.sh -q package   # build the jar
```
Tests never touch the runtime `treasury` DB (isolated to `treasury_test`).

## 4. Run the app

```bash
# offline (stubs) — fast, no chain, no funds:
docker run -d --name treasury-app --network host --env-file .env \
  -v "$PWD/treasury":/app -w /app -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -q spring-boot:run

# real on-chain — add the flags (needs funded wallet + running facilitator):
#   -e X402_ENABLED=true -e ERC8004_ENABLED=true
```
Dashboard: `http://localhost:8090/`. Stop: `docker rm -f treasury-app`.

## 5. Facilitator (on-chain mode)

```bash
docker run -d --name x402-facilitator --restart unless-stopped \
  -v "$PWD/infra/facilitator/config.json:/app/config.json:ro" \
  --env-file .env -p 8080:8080 ghcr.io/x402-rs/x402-facilitator
curl -s localhost:8080/supported     # expect eip155:43113
```
The facilitator's signer wallet (`FACILITATOR_PRIVATE_KEY`) must hold test AVAX. See
`infra/facilitator/README.md`.

## 6. Deploy / seed ERC-8004 (one-time)

```bash
docker run --rm --env-file .env -v "$PWD/contracts/erc8004":/app -w /app -v "$HOME/.npm":/root/.npm \
  node:20 bash -lc "npm install --no-audit --no-fund && npx hardhat run scripts/deploy.js --network fuji"
# copy the printed addresses into .env; read current reputation with scripts/read.js
```
See `contracts/README.md`.

## 7. HTTP API

### `POST /proxy` — make a payment
Headers: `X-Agent-Key: <key>` (required), `Idempotency-Key: <id>` (optional).
Body:
```json
{ "payee": "0x…", "asset": "0x5425…Bc65", "amountAtomic": 100000 }
```
Responses:
| Status | Meaning | Body |
|--------|---------|------|
| 200 | settled | `{intentId, state:"SETTLED", txHash}` |
| 402 | blocked by policy | `{intentId, state:"DENIED", denialReason, denialDetail}` |
| 502 | settlement failed downstream | `{intentId, state:"FAILED"}` |
| 401 | bad/missing agent key | error |
| 400 | invalid body | validation error |

```bash
curl -s -X POST localhost:8090/proxy \
  -H "X-Agent-Key: demo-key-agent-1" -H "Content-Type: application/json" \
  -d '{"payee":"0x6f409644a8a0b598284e8ca1a7562759f2189fbf","asset":"0x5425890298aed601595a70AB815c96711a31Bc65","amountAtomic":100000}'
```

### `GET /api/dashboard/agents` / `GET /api/dashboard/payments`
Read-only JSON for the monitoring dashboard (budget/spend; recent payment feed). Amounts are atomic
(÷1e6 = USDC).

## 7a. Admin API — manage agents & policies

Create and configure agents at runtime (no redeploy, no SQL). Backed by the **admin dashboard** at
`http://localhost:8090/admin.html`. All monetary fields are **atomic units** (the UI converts from USDC).

> ⚠️ **Unauthenticated — local/demo use only.** These endpoints can create agents and mint API keys;
> do not expose the app publicly with them enabled.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/admin/agents` | list agents with full policy + allowlists |
| `POST` | `/api/admin/agents` | create an agent; **returns the API key once** |
| `PUT` | `/api/admin/agents/{id}` | update name / policy / allowlists |
| `POST` | `/api/admin/agents/{id}/rotate-key` | issue a new API key (returns it once) |
| `DELETE` | `/api/admin/agents/{id}` | remove an agent (**409** if it has payment history — ledger is kept intact) |

Create request body:
```json
{
  "id": "research-bot",                 // optional; auto-generated if omitted
  "name": "Research Bot",
  "perTxCapAtomic": 500000,             // 0.50 USDC
  "dailyBudgetAtomic": 5000000,         // 5.00 USDC
  "velocityPerMinute": 5,
  "minReputation": 60,
  "allowedMerchants": ["0x6f40…"],
  "allowedAssets": ["0x5425890298aed601595a70AB815c96711a31Bc65"]
}
```
Create/rotate response (the key is shown **only once** — it's stored as a SHA-256 hash):
```json
{ "apiKey": "atk_9f3c…", "agent": { "id": "research-bot", "name": "Research Bot", ... } }
```
Example:
```bash
curl -s -X POST localhost:8090/api/admin/agents -H "Content-Type: application/json" -d '{
  "name":"Research Bot","perTxCapAtomic":500000,"dailyBudgetAtomic":5000000,
  "velocityPerMinute":5,"minReputation":60,
  "allowedMerchants":["0x6f409644a8a0b598284e8ca1a7562759f2189fbf"],
  "allowedAssets":["0x5425890298aed601595a70AB815c96711a31Bc65"]}'
```
The returned `apiKey` is what the agent sends as `X-Agent-Key` on `POST /proxy`.

## 8. Scripts

| Script | Purpose |
|--------|---------|
| `scripts/dev-db.sh up\|down` | manage Postgres `treasury-pg` (+ create `treasury_test`) |
| `scripts/treasury-mvn.sh <goals>` | run Maven for `treasury/` in a container (`--network host`) |
| `scripts/smoke.sh` | Phase-0 x402 smoke test in a Maven container |
| `contracts/erc8004/scripts/{deploy,read}.js` | deploy registries / read reputation |

## 9. Troubleshooting

- **401 on /proxy** — wrong/missing `X-Agent-Key`. The demo key is `demo-key-agent-1` (seeded on boot
  if absent). For other agents, use the key returned when you created them in the admin dashboard.
- **402 unexpectedly** — check the `denialReason`; for reputation, confirm the payee is registered and
  scored (`scripts/read.js`).
- **settle fails (502)** — treasury wallet lacks USDC, or the facilitator wallet lacks AVAX for gas.
- **facilitator unreachable** — `docker logs x402-facilitator`; confirm `/supported` lists `eip155:43113`.
- **web3j won't resolve / Spring Boot version** — bump in the respective `pom.xml`.
