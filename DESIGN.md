# Agent Treasury — Design Document

> The CFO for your AI agents. Agents get spending *authority*, not private keys. Every
> payment flows through a policy engine that checks budgets, merchant rules, and the
> counterparty's on-chain reputation before a single token moves — and every outcome is
> recorded in a double-entry ledger and fed back into ERC-8004.

**Hackathon:** Speedrun: Agentic Payments (Team1 India)
**Author:** Harsh
**Status:** Design — spikes complete, pre-build
**Last updated:** 2026-06-13

> **Phase-0 reconnaissance is done — see [`SPIKE-FINDINGS.md`](./SPIKE-FINDINGS.md) for the verified
> facts (addresses, ABIs, wire formats, config). Headline change: Mogami is dropped (no Avalanche
> support); we use the x402.rs facilitator + sign EIP-3009 ourselves with web3j.** Sections below are
> updated to match.

---

## 1. Problem & thesis

The default x402 setup gives every agent a funded wallet and a private key. That is fine for a
demo and dangerous in production: a prompt-injected, buggy, or compromised agent can drain the
wallet, pay a malicious counterparty, or loop a microtransaction until the balance is gone. There
is no budget, no allowlist, no notion of *who* is being paid.

The x402 Java ecosystem (notably **Mogami** — client SDK, server SDK, facilitator) already solves
the *mechanics* of paying over HTTP 402. **No one solves the governance.** That is the gap.

**Thesis:** Agent payments need a control plane. Agents should hold *spending authority scoped by
policy*, never raw keys. The treasury holds the keys, evaluates every payment against budget +
velocity + counterparty-reputation rules, signs only on approval, records everything in a real
ledger, and writes feedback back to the reputation registry to close the loop.

This maps directly onto the hackathon's stated bonus: **payments that are identity- and
reputation-aware, combining x402 + ERC-8004.**

### Why this project (strategic fit)

- **Win condition:** combines both protocols (the explicit bonus), demos with a clear
  narrative arc (approve → block-on-reputation → block-on-budget), and answers the question every
  judge has about agent money: *"what stops the agent from overspending or paying a scammer?"*
- **Resume condition:** this is a payments risk/policy engine — idempotency, double-entry
  ledgering, policy evaluation, settlement, reconciliation, custody separation. Exactly the
  system-design surface that Senior Backend (payments) interviews probe. Built in Java/Spring Boot,
  deepening the core stack rather than shipping a throwaway TS demo.

---

## 2. Goals & non-goals

### Goals (must demo)
1. An agent autonomously pays for an x402-gated resource *through the treasury* — no human in the loop.
2. Treasury holds keys; agents authenticate with an API key and hold only a virtual budget.
3. Policy engine enforces: per-tx cap, daily budget, merchant allowlist, velocity limit, and
   **reputation-tiered limits** read from ERC-8004.
4. A payment is **blocked live** because the counterparty's reputation is below threshold.
5. A payment is **blocked live** because the agent's daily budget is exhausted.
6. Double-entry ledger records every intent → settlement with an on-chain tx hash.
7. After settlement, feedback is written to the ERC-8004 reputation registry (score visibly updates).
8. A dashboard shows budget burn-down, the payment feed, and **blocked attempts with the reason**.

### Non-goals (explicitly cut for the sprint)
- Multi-chain / multi-asset. **Single chain, USDC only.**
- Admin UI for policy CRUD. **Policies live in YAML / seeded DB rows.**
- Multi-tenancy / auth hardening beyond a static API key per agent.
- Human-approval escalation queue (mention in Q&A; the theme rewards *no* human in the loop).
- Production key management (HSM/KMS). Use an encrypted local keystore; note the prod path.
- The validation registry (ERC-8004's third registry). Identity + reputation only.

---

## 3. Architecture

```
                        ┌─────────────────── Agent Treasury (Spring Boot) ───────────────────┐
  Agent A ──┐           │                                                                     │
  Agent B ──┼─ HTTP ──▶ │  Proxy ─▶ Policy Engine ─▶ Reputation Oracle ─▶ Signer ─▶ Ledger   │ ──▶ Merchant API (402)
  Agent C ──┘  (API key)│              ▲                    ▲                │                │
                        │       policies (YAML/DB)   ERC-8004 registries     └─ web3j signs  │ ──▶ x402.rs facilitator (settle)
                        │                            (web3j, cached)           EIP-3009 (x402)│
                        └──────────────────────────┬──────────────────────────────────────────┘
                                                   └─▶ Feedback writer ─▶ ERC-8004 reputation registry
```

### Request flow (the spine)

1. Agent calls a merchant URL **through** the treasury proxy, authenticating with its API key.
2. Treasury forwards the request; merchant responds **402 Payment Required** with x402 requirements.
3. Treasury opens a `PaymentIntent` with an **idempotency key** = `hash(agentId + method + url + bodyHash + nonce)`.
4. **Policy engine** evaluates the intent (deny-by-default).
5. **Reputation oracle** resolves the payee's ERC-8004 identity + score (cached) and feeds the tier rule.
6. On **APPROVE**: treasury signs the x402 payment payload (web3j EIP-3009); retries the merchant
   request with the `X-PAYMENT` header; merchant (via the x402.rs facilitator) settles; resource
   returned to agent.
7. **Ledger** records the journal entries; settlement entry stamped with the on-chain tx hash.
8. **Feedback writer** asynchronously submits feedback to the reputation registry.

On **DENY**: intent moves to `DENIED` with a machine-readable reason; agent gets a `402`/`403` with
that reason; dashboard shows it. No funds move.

### PaymentIntent state machine

```
REQUESTED ─▶ APPROVED ─▶ SIGNED ─▶ SETTLED
    │            │           │         
    └─▶ DENIED   └─▶ DENIED  └─▶ FAILED ─▶ (reconciliation)
```

- Idempotency: a retried agent request with the same key returns the existing intent's outcome —
  **never double-pays**. This is the single most important correctness property and the best
  interview story in the project.
- `FAILED` (signed but settlement unconfirmed) is resolved by the reconciliation job, not by retry.

---

## 4. Components

### 4.1 Proxy / ingress
- Spring Boot controller that accepts `POST /proxy` with target URL + method + headers + body
  (or a transparent forwarding proxy if time allows — the explicit endpoint is simpler and demos fine).
- Authenticates the agent via `X-Agent-Key` header → resolves `AgentId`.
- **Java 21 virtual threads**: the proxy holds a connection open while awaiting policy + chain
  reads + settlement. Classic blocking-IO-fan-out — a textbook virtual-threads use case. Call this
  out in the demo.

### 4.2 Policy engine
Declarative policy per agent, evaluated in order, deny-by-default:

| Rule | Example | Denial reason |
|------|---------|---------------|
| Per-tx cap | ≤ $0.50 / call | `PER_TX_CAP_EXCEEDED` |
| Daily budget | ≤ $5.00 / UTC day | `DAILY_BUDGET_EXHAUSTED` |
| Merchant allowlist | payee ∈ {…} | `MERCHANT_NOT_ALLOWED` |
| Asset restriction | USDC only | `ASSET_NOT_ALLOWED` |
| Velocity | ≤ N payments / minute | `VELOCITY_LIMIT_EXCEEDED` |
| Reputation tier | see below | `REPUTATION_BELOW_THRESHOLD` |

**Reputation tiers** (read from ERC-8004, applied as a multiplier on the agent's limits):

| Counterparty score | Effect |
|--------------------|--------|
| ≥ 80 | full limits |
| 40–79 | 25% of limits |
| < 40 or unknown | **deny** |

Policy is a pure function `evaluate(intent, agentPolicy, reputation) → Decision{allow, reason}`.
Pure + unit-testable; no IO inside. This keeps the demo deterministic and the tests fast.

### 4.3 Reputation oracle (ERC-8004 integration)
- **web3j** reads against the deployed ERC-8004 registries.
- Identity Registry (ERC-721): resolve payee wallet → `agentId` → registration metadata.
- Reputation Registry: `getSummary(agentId, clients[], tag1, tag2) → (count, summaryValue, decimals)`.
  `summaryValue` is an **average** in fixed-point; real value = `summaryValue / 10^decimals`. The
  contract enforces **no** 0–100 range — it's our convention. **Decision:** we write feedback as an
  integer **0–100 with `valueDecimals=0`**, so `getSummary` returns the average score directly;
  `count==0` (unknown agent) → **deny**. Clamp to `[0,100]`. (Full semantics in `SPIKE-FINDINGS.md` §4.)
- **Caching:** Caffeine cache, short TTL (e.g. 30s). One RPC round-trip per payment is too slow and
  rate-limited on public testnet RPCs. Cache-freshness-vs-correctness is a deliberate trade-off —
  document it (a stale-but-recent reputation read is acceptable; a slow one breaks the demo).
- **Feedback writer:** after settlement, `giveFeedback(...)` on the reputation registry (signed by
  the treasury wallet, which is itself a registered agent). Async, off the request path.

Verified ERC-8004 surface (exact signatures + deploy steps in `SPIKE-FINDINGS.md` §4):
- Identity (ERC-721 + UUPS): `register(...)` (overloaded), `getAgentWallet(agentId)`, `getMetadata/setMetadata`.
- Reputation: `giveFeedback(agentId,int128 value,uint8 decimals,tag1,tag2,endpoint,uri,hash)`,
  `getSummary(...)`, `readFeedback(...)`. Deploy order: **Identity first**, then
  `Reputation.initialize(identityAddr)`. Toolchain: Hardhat v3, Solidity 0.8.24; Fuji already in config.
- (Validation registry exists but is out of scope.)

> **Day-0 spike required:** the ERC-8004 reference deployments are on Ethereum Sepolia / Polygon
> Amoy — **not Fuji**. We **deploy the reference registries ourselves** to Fuji (Foundry/Hardhat;
> C-Chain is EVM so they compile + deploy unchanged), then generate web3j wrappers from the ABIs and
> seed two merchant agents (high/low reputation) for the demo.

### 4.4 Signer & x402 (Avalanche Fuji) — **RESOLVED**
- Treasury holds the omnibus wallet key in an encrypted local keystore (env-injected passphrase).
- **Client-side signing — web3j, ourselves.** (Mogami is dropped: hardcoded to Base+Solana, no Fuji.)
  We sign the **EIP-3009 `transferWithAuthorization`** payload with web3j EIP-712 typed data, then
  base64-encode the x402 `X-PAYMENT` header. This is now the primary path, not a fallback — it's one
  fewer dependency and a stronger resume line ("implemented the x402 client over EIP-3009 in Java").
  - Domain (verified on-chain): `{name:"USD Coin", version:"2", chainId:43113, verifyingContract: Fuji USDC}`.
  - Pack signature `r||s||v` with **v ∈ {27,28}**. Exact schemas in `SPIKE-FINDINGS.md` §3.
- **Facilitator = x402.rs**, self-hosted via Docker (`ghcr.io/x402-rs/x402-facilitator`), pointed at
  Fuji via `config.json` (`eip155:43113`). Source-confirmed Fuji support. `POST /verify` + `POST /settle`.
  Separate process, language-irrelevant — the treasury stays pure Spring Boot.
- **Gas / custody model:** payer (treasury wallet) signs but pays **no gas** — the facilitator's
  signer wallet submits the tx and pays AVAX gas (x402 gasless model). So: treasury wallet needs
  **USDC** (to pay merchants) + a little **AVAX** (only for its *own* ERC-8004 feedback writes &
  deploys); facilitator wallet needs **AVAX** (to sponsor settlement gas).

### 4.5 Ledger
- **Double-entry**, append-only journal. Two accounts minimum per agent:
  - `agent:{id}:budget` (asset account, debited on payment)
  - `merchant:payable` (liability/clearing, credited on payment, cleared on settlement)
- Each `PaymentIntent` produces balanced journal entries; settlement entry carries the tx hash.
- Daily budget = sum of debits to `agent:{id}:budget` for the current UTC day. (Or a materialized
  counter for speed; keep the journal as source of truth.)
- **Reconciliation job** (`@Scheduled`): re-verify `SETTLED`/`FAILED` intents against on-chain state,
  flag mismatches. Five lines of scheduler code, large credibility payoff — "we don't trust our own
  ledger blindly" is how real payment systems think.

### 4.6 Dashboard
- Budget burn-down per agent, live payment feed, blocked-attempts feed **with reason** (the money shot).
- Server-rendered (Thymeleaf) or a thin React page polling a `/events` endpoint. Do not over-invest.

---

## 5. Tech stack

| Concern | Choice | Note |
|---------|--------|------|
| Language/runtime | Java 21 | virtual threads for the proxy |
| Framework | Spring Boot 3.x | core competency + the differentiator |
| Persistence | PostgreSQL | ledger + intents; Docker for local |
| Chain access | web3j | ERC-8004 reads/writes |
| x402 client signing | **web3j** (EIP-3009 / EIP-712) | ours; Mogami dropped (no Fuji) |
| x402 facilitator | **x402.rs** (self-hosted Docker) | Fuji-confirmed; separate process |
| Cache | Caffeine | reputation read cache |
| Chain | **Avalanche Fuji C-Chain** (chainId 43113) | EVM; required by submission rules |
| Asset | **USDC on Fuji** (Circle faucet) | EIP-3009 gasless; single asset |
| Dashboard | Thymeleaf or thin React | low investment |

> **Chain is locked** to Avalanche Fuji (C-Chain testnet) by the submission rules. C-Chain is
> EVM-compatible, so web3j + Solidity ERC-8004 contracts work unchanged. Mainnet deploy is an
> optional bonus (§12). Sub-second finality makes for a crisp live demo.

---

## 6. Data model (first cut)

```
agent            (id, name, api_key_hash, policy_id, created_at)
agent_policy     (id, per_tx_cap, daily_budget, velocity_per_min,
                  allowed_merchants[], allowed_assets[], min_reputation)
payment_intent   (id, agent_id, payee, asset, amount, idempotency_key UNIQUE,
                  state, denial_reason, tx_hash, created_at, updated_at)
journal_entry    (id, intent_id, account, debit, credit, created_at)
reputation_cache (payee, agent_id, score, fetched_at)   -- or Caffeine in-memory
```

`idempotency_key UNIQUE` is the database-level guarantee behind exactly-once payment.

---

## 7. Demo script (~3 min — has a plot)

1. **Setup on screen:** two merchant agents registered in ERC-8004 — `good-data-co` (rep 85) and
   `sketchy-data-inc` (rep 12). One research agent: $5 daily budget, `minReputation: 60`.
2. **Happy path:** research agent autonomously buys market data via the treasury. Show the 402
   intercept → policy pass → tx hash on the Snowtrace (Fuji) explorer → ledger entry → **feedback tx
   bumping `good-data-co`'s score**. Talking points: settlement is **gasless for the payer** (USDC
   EIP-3009; facilitator sponsors gas) and confirms in **<1s** on Avalanche.
3. **The block (reputation):** agent tries `sketchy-data-inc`. Dashboard: `DENIED — counterparty
   reputation 12 below threshold 60`. No human intervened; the *system* protected the money.
4. **The block (budget):** loop the agent until it hits the daily cap → `DENIED — daily budget
   exhausted`, burn-down chart at zero.
5. **Close:** *"Real value moved, no human in the loop — and no way for the agent to move value it
   shouldn't."* That last beat is the differentiator; most demos only have step 2.

**Demo insurance:** keep a screen recording of a clean run; seed the testnet wallet generously the
night before (faucets + public RPCs are the #1 live-demo killers); pin RPC endpoint + have a backup.

---

## 8. Build plan (phased — assumes a short sprint)

### Phase 0 — Spikes
**Reconnaissance done** (see `SPIKE-FINDINGS.md`) — Mogami ruled out, x402.rs + web3j chosen, all
addresses/ABIs/wire-formats verified. Remaining Phase-0 items need a funded wallet (see
`SPIKE-FINDINGS.md` §6 for the live-execution checklist):
- [ ] Fund treasury + facilitator wallets (test AVAX) and treasury (test USDC) — faucets.
- [ ] Deploy ERC-8004 (Identity → Reputation) to Fuji; record proxy addresses; seed two merchants.
- [ ] Run x402.rs against Fuji; `GET /supported` shows `eip155:43113`.
- [ ] Smoke test: web3j-sign one EIP-3009 payment → `POST /verify` (`isValid:true`) → `POST /settle`
      → confirm tx on Snowtrace. **This validates the v-packing / EIP-712 domain — highest-risk detail.**

### Phase 1 — Core path (no chain)
- [ ] Spring Boot skeleton, Postgres, agent + policy seed data.
- [ ] Proxy endpoint + API-key auth + virtual threads.
- [ ] `PaymentIntent` state machine + idempotency (unique key).
- [ ] Policy engine as a pure function + unit tests (this is where the test coverage goes).
- [ ] Double-entry ledger + daily-budget computation.

### Phase 2 — Chain integration
- [ ] Wire the web3j x402 client: real 402 → EIP-3009 sign → `X-PAYMENT` → facilitator settle.
- [ ] Reputation oracle (web3j read) + Caffeine cache + tier rule.
- [ ] Feedback writer (async, post-settlement).
- [ ] Reconciliation `@Scheduled` job.

### Phase 3 — Demo polish
- [ ] Dashboard: burn-down + payment feed + blocked-with-reason.
- [ ] Demo agent(s) — TS/Python using official x402 SDK, or Java/Spring AI.
- [ ] Seed the two merchant scenarios; rehearse the script; record the backup video.

**Cut order if time runs short:** dashboard polish → reconciliation job → feedback writer →
velocity rule. Never cut: idempotency, policy engine, the two live blocks, one real on-chain settlement.

---

## 9. Risks

| Risk | Mitigation |
|------|------------|
| EIP-712 signature silently invalid (wrong domain / `v`) | **Highest risk.** Domain verified on-chain; validate via `/verify` in Phase-0 before building on top |
| x402.rs Fuji config / self-host effort | Config verified (`SPIKE-FINDINGS.md` §2); fallback = hosted x402.rs if `/supported` lists Fuji |
| Fuji RPC flakiness / faucet limits | Pin RPC, pre-fund AVAX + USDC the night before, backup video |
| ERC-8004 deploy (vanity/CREATE2 complexity) | Skip vanity; deploy plain UUPS proxies ourselves (canonical address not needed) |
| Live multi-agent demo breaks | Single research agent + scripted merchants; recorded fallback |
| Scope creep (validation registry, multi-chain) | Explicit non-goals in §2 |

---

## 10. Resume translation

Polished bullets (all of this is built and verified on Avalanche Fuji, not aspirational):

- Built a **policy-enforcement payment gateway** in **Java 21 / Spring Boot** that lets autonomous AI
  agents transact under per-tx, daily-budget, velocity, and counterparty-reputation constraints —
  **agents hold spending authority, never private keys** (the treasury custodies keys and signs only
  after policy approval).
- Implemented an **idempotent payment-intent state machine** + **double-entry ledger** (Postgres,
  Flyway) with a **scheduled on-chain reconciliation** job that re-verifies settlements against chain
  receipts — defense-in-depth against ledger/chain drift.
- Integrated the **x402** protocol end-to-end: signed **EIP-3009 (`transferWithAuthorization`)**
  authorizations with web3j and settled real USDC via an x402 facilitator (gasless for the payer).
- Integrated **ERC-8004** on-chain identity + reputation as a real-time risk signal (web3j reads,
  Caffeine-cached) and **closed the trust loop** by writing `giveFeedback` after each payment, so a
  counterparty's on-chain reputation reflects its transaction history.
- Designed the system so the chain layer sits behind interfaces (`PaymentExecutor`,
  `ReputationProvider`, `FeedbackWriter`), keeping a **35-test suite** fully offline/deterministic
  while the same code runs live on-chain via config flags.

Interview stories it generates: exactly-once payment semantics (idempotency key + unique constraint);
custody separation (authority vs. keys); EIP-712 signing pitfalls (domain/`v` make-or-break);
cache-freshness vs. correctness on risk reads; reconciliation as defense-in-depth; and a real bug —
test/runtime DB pollution causing rolled-back denials, fixed by DB isolation + an upserting seeder.

One-line résumé summary: *"Built the risk & control layer for autonomous agent payments — x402
settlement gated by ERC-8004 reputation and spend policy, on Avalanche; Java/Spring Boot, web3j."*

---

## 11. Open questions (resolve before/early in build)

1. ~~Chain + facilitator~~ **RESOLVED:** Avalanche Fuji C-Chain; facilitator = self-hosted x402.rs.
2. ~~Mogami vs web3j~~ **RESOLVED:** Mogami has no Fuji support → we sign EIP-3009 with web3j.
3. ~~Are ERC-8004 registries on the target chain?~~ **RESOLVED:** no — we deploy them to Fuji.
4. ~~Reputation normalization~~ **RESOLVED:** write feedback as 0–100 int (`valueDecimals=0`);
   `getSummary` returns the average directly; `count==0` → deny. (`SPIKE-FINDINGS.md` §4.)
5. Transparent proxy vs explicit `/proxy` endpoint — explicit is simpler; confirm the demo agents
   can be pointed at it. *(Lean: explicit `/proxy`.)*
6. Hosted x402.rs `/supported` includes `eip155:43113`? If yes, can skip self-hosting for v0.
   (Cheap to check during Phase-0; self-hosting is the safe default regardless.)

---

## 12. Submission requirements (hackathon)

From the Speedrun page — bake these into the build so submission is a formality:

- [ ] **Working prototype deployed on Avalanche C-Chain**, demoed on **Fuji testnet** with free test AVAX.
- [ ] **Live demo** showing agents paying / getting paid / transacting **autonomously** (no human in loop).
- [ ] **Mainnet deploy is optional → bonus points.** Plan for Fuji; only attempt mainnet if everything
      else is solid and time remains.
- [ ] **Make the delta obvious.** This project is **built fresh during the Speedrun** — the entire
      prototype is the delta; nothing pre-existing is claimed. State this explicitly in the submission.
- [ ] Original / out-of-the-box framing encouraged — the "treasury / CFO-for-agents governance layer"
      angle is the differentiator vs. the listed ideas; lead with it.

---

## Appendix A — References
- **Verified build facts:** [`SPIKE-FINDINGS.md`](./SPIKE-FINDINGS.md) (this repo)
- Avalanche x402 academy: https://build.avax.network/academy/blockchain/x402-payment-infrastructure/
- x402.rs facilitator: https://github.com/x402-rs/x402-rs · https://facilitator.x402.rs/
- ERC-8004 reference contracts: https://github.com/erc-8004/erc-8004-contracts · spec https://eips.ethereum.org/EIPS/eip-8004
- x402 protocol: https://github.com/x402-foundation/x402 · EIP-3009: https://eips.ethereum.org/EIPS/eip-3009
- Faucets: test AVAX `build.avax.network/console/primary-network/faucet` · test USDC `faucet.circle.com` (Fuji)
- Mogami (evaluated, **not used** — no Avalanche support): https://mogami.tech/
