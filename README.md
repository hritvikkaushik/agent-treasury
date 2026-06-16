# Agent Treasury

> The control plane for autonomous agent payments. Agents get spending **authority, not private
> keys** — every payment routes through a policy engine (budgets, velocity, merchant allowlist,
> on-chain reputation) before the treasury signs and settles on **Avalanche Fuji**.

Built for the *Speedrun: Agentic Payments* hackathon. Combines **x402** (HTTP-native stablecoin
payments) with **ERC-8004** (on-chain agent identity + reputation). An autonomous agent pays real USDC,
gated by on-chain reputation and spend policy — no human in the loop.

## Documentation
| Doc | What |
|-----|------|
| [docs/HLD.md](./docs/HLD.md) | High-level design — problem, components, decisions |
| [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) | Modules, interface seams, data model, topology |
| [docs/LLD.md](./docs/LLD.md) | Low-level — classes, algorithms, schema |
| [docs/FLOWS.md](./docs/FLOWS.md) | Runtime sequences (payment, denial, reputation, reconciliation) |
| [docs/USAGE.md](./docs/USAGE.md) | Build/run/config, env reference, HTTP API |
| [docs/USER-GUIDE.md](./docs/USER-GUIDE.md) | For agent developers — how to pay, outcomes, limits |
| [DESIGN.md](./DESIGN.md) | Original design doc & rationale + résumé bullets |
| [DEMO.md](./DEMO.md) | Live demo script |
| [SPIKE-FINDINGS.md](./SPIKE-FINDINGS.md) | Verified on-chain facts (addresses, ABIs, wire formats) |
| [RESUME-PLAN.md](./RESUME-PLAN.md) | Live status + plan (source of truth for progress) |

## Repository layout
```
.
├── docs/                  # HLD, LLD, ARCHITECTURE, FLOWS, USAGE, USER-GUIDE
├── treasury/              # Spring Boot app (Java 21): policy, ledger, intents, proxy, dashboard
├── contracts/erc8004/     # lean ERC-8004-compatible registries + Hardhat deploy/read scripts
├── smoke-test/            # Phase-0: web3j EIP-3009 signing → facilitator verify/settle
├── infra/facilitator/     # x402.rs facilitator config for Fuji
├── scripts/               # dev-db.sh, treasury-mvn.sh, smoke.sh
├── DESIGN.md SPIKE-FINDINGS.md RESUME-PLAN.md DEMO.md CLAUDE.md
└── .env.example           # config template (copy to .env; never commit .env)
```

## Prerequisites
| Tool | Version | For |
|------|---------|-----|
| Docker | recent | facilitator, Postgres, and containerized builds |
| Node | 20+ | only for the ERC-8004 deploy (or run it in Docker) |
| Java + Maven | 21 / 3.9 | only if building on the host instead of via Docker |
| git | any | — |

Plus a **funded Fuji wallet** for on-chain mode: test AVAX
([Core console faucet](https://build.avax.network/console/primary-network/faucet)) and test USDC
([Circle faucet](https://faucet.circle.com/), select Avalanche Fuji).

## Quick start
```bash
cp .env.example .env                 # fill throwaway testnet keys + addresses
./scripts/dev-db.sh up               # Postgres (+ treasury_test for tests)
./scripts/treasury-mvn.sh -q test    # 38 tests, offline

# Run the app (offline/stub mode — no chain, no funds):
docker run -d --name treasury-app --network host --env-file .env \
  -v "$PWD/treasury":/app -w /app -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -q spring-boot:run
# Monitoring dashboard: http://localhost:8090/   ·   Admin (add/configure agents): http://localhost:8090/admin.html
# add -e X402_ENABLED=true -e ERC8004_ENABLED=true for real chain
```
Full instructions: [docs/USAGE.md](./docs/USAGE.md). Demo: [DEMO.md](./DEMO.md).

## Status
**Complete and demo-ready.** Core agentic-payments loop verified end-to-end on Avalanche Fuji: x402
USDC settlement gated by ERC-8004 reputation + spend policy, with a feedback loop that raises a
merchant's on-chain reputation, a live dashboard, idempotent double-entry ledger, and scheduled
reconciliation. 38 tests green (offline). Remaining items are optional polish — see
[RESUME-PLAN.md](./RESUME-PLAN.md).
