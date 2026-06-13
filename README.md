# Agent Treasury

> The CFO for your AI agents. Agents get spending **authority, not private keys** — every x402
> payment routes through a policy engine (budgets, velocity, merchant allowlist, on-chain reputation)
> before the treasury signs and settles on **Avalanche Fuji**.

Built for the *Speedrun: Agentic Payments* hackathon. Combines **x402** (HTTP-native stablecoin
payments) with **ERC-8004** (on-chain agent identity + reputation).

- **Design & rationale:** [`DESIGN.md`](./DESIGN.md)
- **Verified build facts** (addresses, ABIs, wire formats): [`SPIKE-FINDINGS.md`](./SPIKE-FINDINGS.md)
- **Live plan & progress:** [`RESUME-PLAN.md`](./RESUME-PLAN.md)

## Repository layout
```
.
├── DESIGN.md              # architecture, components, demo script
├── SPIKE-FINDINGS.md      # verified facts — ground truth for all on-chain values
├── RESUME-PLAN.md         # live status + step-by-step plan (keep updated)
├── CLAUDE.md              # orientation for AI agents
├── .env.example           # config template (copy to .env; never commit .env)
├── .claude/
│   └── settings.json.example   # recommended permissions (copy to settings.json to enable)
├── smoke-test/            # Phase-0: web3j EIP-3009 signing → facilitator verify/settle
├── infra/facilitator/     # x402.rs facilitator config for Fuji
└── contracts/             # ERC-8004 deploy-to-Fuji notes
    (treasury Spring Boot app — added in Phase 1)
```

## Prerequisites
| Tool | Version | For |
|------|---------|-----|
| Java | 21 | treasury + smoke-test |
| Maven | 3.9+ | build |
| Node | 20+ | ERC-8004 Hardhat deploy |
| Docker | any recent | x402.rs facilitator (Linux) |
| git | any | — |

Plus a **funded Fuji wallet**: test AVAX ([Core console faucet](https://build.avax.network/console/primary-network/faucet))
and test USDC ([Circle faucet](https://faucet.circle.com/), select Avalanche Fuji).

## Quick start
```bash
# 1. Config
cp .env.example .env            # fill in throwaway testnet keys + PAY_TO
cp .claude/settings.json.example .claude/settings.json   # optional: pre-approve dev commands

# 2. Run the x402.rs facilitator against Fuji (Linux/Docker) — see infra/facilitator/README.md
docker run -d --name x402-facilitator --restart unless-stopped \
  -v "$(pwd)/infra/facilitator/config.json:/app/config.json:ro" \
  --env-file .env -p 8080:8080 ghcr.io/x402-rs/x402-facilitator
curl -s localhost:8080/supported   # confirm eip155:43113

# 3. Smoke test: sign one EIP-3009 payment and push it through the facilitator
#    Runs in a Maven+JDK21 container (host needs only Docker). Do NOT `source .env`.
./scripts/smoke.sh
# -> prints signature, X-PAYMENT header, /verify result, /settle tx hash (Snowtrace link)
```

See [`RESUME-PLAN.md`](./RESUME-PLAN.md) for the full Phase-0 checklist and what comes next.

## Status
Phase-0 reconnaissance complete; smoke-test bundle written. Live execution (faucets, facilitator run,
ERC-8004 deploy, settle) pending — tracked in `RESUME-PLAN.md`. Treasury app not yet started.
