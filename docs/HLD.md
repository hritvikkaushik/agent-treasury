# High-Level Design — Agent Treasury

> The control plane for autonomous agent payments. Agents get spending **authority, not private
> keys**; every payment is gated by policy (budget, velocity, counterparty reputation) before the
> treasury signs and settles. Built on **x402** (HTTP-native stablecoin payments) + **ERC-8004**
> (on-chain agent identity & reputation), on **Avalanche Fuji**.

See also: [ARCHITECTURE](./ARCHITECTURE.md) · [LLD](./LLD.md) · [FLOWS](./FLOWS.md) ·
[USAGE](./USAGE.md) · [USER-GUIDE](./USER-GUIDE.md) · design rationale in [`../DESIGN.md`](../DESIGN.md).

## 1. Problem

The default agent-payments setup hands each agent a funded wallet and a private key. A buggy,
prompt-injected, or compromised agent can then drain the wallet, pay a malicious counterparty, or loop
microtransactions. There is no budget, no allowlist, no notion of *who* is being paid. x402 solves the
*mechanics* of paying over HTTP; nothing solves the *governance*.

## 2. Thesis & goals

**Agent payments need a control plane.** Agents hold scoped spending authority; the treasury holds
keys, evaluates every payment against policy and on-chain counterparty reputation, signs only on
approval, records everything in a double-entry ledger, and writes feedback back on-chain.

Goals (all met and verified on Fuji):
- An agent autonomously pays for a resource through the treasury — no human in the loop.
- Treasury custodies keys; agents authenticate with an API key.
- Policy enforces per-tx cap, daily budget, merchant allowlist, velocity, and reputation tiers.
- Payments are blocked live on reputation and on budget, each with a machine-readable reason.
- Double-entry ledger; settlement carries the on-chain tx hash.
- Post-settlement feedback raises the counterparty's on-chain reputation.
- A dashboard shows budget burn-down, the payment feed, and blocked attempts with reasons.

Non-goals: multi-chain/multi-asset, policy-admin UI, multi-tenancy, the ERC-8004 validation registry,
production key management (HSM/KMS).

## 3. System context

```
         ┌──────────┐   HTTP + X-Agent-Key    ┌──────────────────────────┐
         │ AI agent │ ───────────────────────▶ │      Agent Treasury       │
         └──────────┘   POST /proxy            │     (Spring Boot, Java 21) │
                                               │                            │
   ┌──────────────┐  read getSummary (eth_call)│  policy · ledger · intents │
   │  ERC-8004     │◀───────────────────────────│                            │
   │  registries   │  write giveFeedback (tx)   │                            │
   │  (Fuji)       │◀───────────────────────────│                            │
   └──────────────┘                            │                            │
                                               │   sign EIP-3009            │
   ┌──────────────┐  POST /settle              │      │                     │
   │ x402.rs       │◀───────────────────────────┘      ▼                     │
   │ facilitator   │  transferWithAuthorization ──▶ Avalanche Fuji (USDC)    │
   └──────────────┘     (facilitator pays gas)                               │
                                                ┌────────────┐
                                                │ PostgreSQL  │ ledger + intents
                                                └────────────┘
```

## 4. Major components

| Component | Responsibility |
|-----------|----------------|
| **Proxy / API** (`api`) | Agent ingress (`POST /proxy`), API-key auth, dashboard JSON, static UI |
| **Orchestrator** (`service.TreasuryService`) | Coordinates: idempotent intent → policy → settle → ledger → feedback |
| **Policy engine** (`policy`) | Pure, deterministic evaluation of all spend rules; deny-by-default |
| **Ledger** (`ledger`) | Double-entry journal; daily-spend query |
| **Intent lifecycle** (`intent`, `domain.PaymentIntent`) | State machine + idempotency |
| **Payment executor** (`payment`) | Sign EIP-3009 + settle via facilitator (real) / stub |
| **Reputation** (`reputation`) | Read `getSummary` (real/stub) + write `giveFeedback` (async) |
| **Reconciliation** (`reconcile`) | Scheduled re-verification of settlements vs chain |
| **Persistence** | PostgreSQL + Flyway |

## 5. External systems

- **Avalanche Fuji C-Chain** (chainId 43113) — EVM testnet. RPC `https://api.avax-test.network/ext/bc/C/rpc`.
- **USDC (Fuji)** `0x5425890298aed601595a70AB815c96711a31Bc65` — EIP-3009 capable, 6 decimals.
- **x402.rs facilitator** — self-hosted Docker service; verifies + settles (pays gas).
- **ERC-8004 registries** (our lean compatible deploy) — Identity `0x313b59f6…8598`,
  Reputation `0x0455293B…5A3a`.

## 6. Key design decisions

1. **Custody separation** — agents authenticate; the treasury holds keys and signs only after policy
   approval. The single most important property.
2. **Guardrail before settlement** — policy evaluation short-circuits; a denied payment never reaches
   the signing/chain path.
3. **Chain behind interfaces** — `PaymentExecutor`, `ReputationProvider`, `FeedbackWriter`,
   `ReceiptStatusProvider`. Stubs by default → the test suite is fully offline/deterministic; real
   implementations activate via `x402.enabled` / `erc8004.enabled` flags.
4. **Idempotency** — a unique idempotency key + DB constraint give exactly-once payment semantics.
5. **Double-entry + reconciliation** — the ledger is the source of truth, and a scheduled job
   re-verifies settlements against the chain (don't trust our own ledger blindly).
6. **Lean ERC-8004 registries** — we deploy compatible registries (same `register`/`giveFeedback`/
   `getSummary` signatures) rather than the full UUPS/vanity reference impl — pragmatic for the sprint;
   the treasury integration is real either way.

## 7. Non-functional notes

- **Security/custody:** no agent ever holds a key; API keys are stored only as SHA-256 hashes; secrets
  live in `.env` (gitignored).
- **Concurrency:** the proxy runs on Java 21 virtual threads (blocking fan-out per request: policy +
  chain reads + settlement).
- **Reliability:** fail-closed reputation reads (unknown → deny); best-effort async feedback never
  fails a payment; reconciliation flags ledger/chain drift.
- **Testability:** 38 tests, all offline (stubs + a separate `treasury_test` database).

## 8. Deployment topology (dev / demo)

Two-machine dev loop: code is authored on a Mac and run on a Linux box over SSH; the host needs only
Docker. Runtime pieces: the Spring app (Maven container, `--network host`, port 8090), PostgreSQL
(`treasury-pg`), and the x402.rs facilitator (`x402-facilitator`, port 8080). See [USAGE](./USAGE.md).
