# CLAUDE.md — orientation for AI agents working in this repo

**Read this first, then read [`RESUME-PLAN.md`](./RESUME-PLAN.md)** — that file is the live source of
truth for what's done, what's next, and how to continue. Keep it updated as you work.

## What this is
**Agent Treasury** — a governance / control-plane layer for autonomous AI-agent payments, built for
the *Speedrun: Agentic Payments* hackathon (Avalanche). Agents get spending **authority, not private
keys**: every x402 payment routes through a policy engine (budgets, velocity, merchant allowlist,
ERC-8004 counterparty reputation) before the treasury signs and settles. Full motivation + design in
[`DESIGN.md`](./DESIGN.md).

## Document map
| File | Purpose |
|------|---------|
| `RESUME-PLAN.md` | **Live state + step-by-step plan. Start here. Update as you go.** |
| `docs/HLD.md`, `docs/ARCHITECTURE.md`, `docs/LLD.md` | Design at three altitudes |
| `docs/FLOWS.md` | Runtime sequences (payment, denial, reputation, feedback, reconciliation) |
| `docs/USAGE.md` | Build/run/config + HTTP API reference |
| `docs/USER-GUIDE.md` | For agent developers using the treasury |
| `DESIGN.md` | Original design doc & rationale + résumé bullets. |
| `SPIKE-FINDINGS.md` | Verified facts: addresses, ABIs, x402 wire formats, configs. **Ground truth for values.** |
| `DEMO.md` | Live demo script (committed). *(A private `DEMO-GUIDE.private.md` is gitignored.)* |
| `README.md` | Human setup / how-to-run + doc index. |
| `treasury/` | The Spring Boot app (policy, ledger, intents, proxy, dashboard, chain integration). |
| `smoke-test/` | Phase-0 Java/web3j harness: sign EIP-3009 → facilitator `/verify` → `/settle`. |
| `infra/facilitator/` | x402.rs facilitator config for Fuji + run notes. |
| `contracts/erc8004/` | Lean ERC-8004-compatible registries + Hardhat deploy/read scripts. |

## Key locked decisions (do not re-litigate — see SPIKE-FINDINGS.md)
- Chain: **Avalanche Fuji** (chainId 43113). EVM.
- Facilitator: **self-hosted x402.rs** (Docker). Not Mogami (no Avalanche support).
- Client signing: **web3j ourselves** (EIP-712 over EIP-3009). Not a third-party SDK.
- Wire format: **x402 v1** (`network: "avalanche-fuji"`, `maxAmountRequired`).
- Reputation: write feedback as **0–100 int, `valueDecimals=0`**; `getSummary` returns the average; unknown agent (`count==0`) → deny.

## Conventions
- **Java 21 / Spring Boot 3** for the treasury (use virtual threads for the proxy). Maven.
- **Never commit secrets.** Private keys live in `.env` (gitignored). Use throwaway testnet keys.
- All on-chain values (USDC address, EIP-712 domain `name`/`version`) are in `SPIKE-FINDINGS.md` and
  were verified on-chain — don't substitute remembered values; a wrong EIP-712 domain silently
  invalidates signatures.
- The smoke-test `Eip3009Signer` is meant to port directly into the treasury later — keep it clean.

## Environment prerequisites
The **Linux runtime box (`homelinux`) has only Docker** — no Java/Maven/Node. So **builds run in
containers**, not on the host: the smoke test uses a `maven:3.9-eclipse-temurin-21` image
(`scripts/smoke.sh`), the facilitator is a container (`infra/facilitator/docker-compose.yml`), and the
ERC-8004 deploy should use a `node` image. Keep this Docker-only pattern for anything new.
A funded Fuji wallet (test AVAX + test USDC) is needed for the live Phase-0 steps.
