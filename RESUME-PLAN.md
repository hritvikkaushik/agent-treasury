# Resume Plan — Agent Treasury

> **Purpose:** this is the live state machine for the project. An agent (or human) picking up the work
> should read this top-to-bottom, do the **Next action**, and then **update the STATE block + Progress
> log** below before stopping. Treat checkboxes as authoritative.

---

## STATE (update this block every session)

- **Phase:** 2 (chain integration). Phase 1 complete (31 tests). **Real x402 settlement AND real
  ERC-8004 reputation both done + live-verified together.**
- **Last completed:** Async ERC-8004 feedback writer. **Full reputation loop live-verified**: agent
  pays good-data-co 2× → real x402 settles → treasury writes on-chain feedback → reputation rose
  85→90 (txs `0x90e48e8d…`, `0x6ce73dea…`). Both protocols real, no human in the loop. 33 offline
  tests green. (Earlier this phase: ERC-8004 deployed; real settlement + reputation gating verified.)
- **Next action:** (a) reconciliation `@Scheduled` job (re-verify settled/failed vs chain);
  (b) cleanups — move executor network call outside the DB tx, feedback-writer nonce sequencing.
  Core loop + dashboard are demo-ready; these are hardening/polish.
- **Blockers:** none. Full demo runs: `./scripts/dev-db.sh up`, run app (with X402_ENABLED=true
  ERC8004_ENABLED=true for real chain, or defaults for offline), open `http://localhost:8090/`.

Wallet roles recap: **treasury `0x44bbaa…`** = payer (USDC ✓ + AVAX ✓);
**facilitator `0x6f40…`** = settlement submitter (AVAX ✓) and current `PAY_TO`.

---

## Phase 0 — Spikes & foundation

### Done (reconnaissance + scaffolding)
- [x] Resolve protocol/chain unknowns → Avalanche Fuji, x402.rs facilitator, web3j signing, x402 v1.
- [x] Verify all on-chain values (USDC addr, EIP-712 domain, ABIs) → `SPIKE-FINDINGS.md`.
- [x] Repo scaffolding: git, `.gitignore`, `.env.example`, `CLAUDE.md`, `README.md`, `.claude` template.
- [x] Phase-0 smoke-test bundle (`smoke-test/`) — web3j EIP-3009 signer + facilitator client.
- [x] Facilitator config + run notes (`infra/facilitator/`).
- [x] ERC-8004 deploy-to-Fuji notes (`contracts/`).

### To do (live — needs funded wallet; see `SPIKE-FINDINGS.md` §6)
- [ ] **Wallets:** create 2 throwaway keys → `.env` (`TREASURY_PRIVATE_KEY`, `FACILITATOR_PRIVATE_KEY`),
      set `PAY_TO`. Fund treasury with test USDC + AVAX; facilitator with AVAX.
- [x] **Facilitator:** running on Fuji via `docker run -d` (`infra/facilitator/`). `/supported`
      confirms v1 `avalanche-fuji` + v2 `eip155:43113`.
- [x] **Smoke test:** PASSED. `/verify isValid:true`, `/settle success:true`, on-chain status 0x1
      (tx `0x81296747…b7230d95`). Go/no-go gate cleared — the full sign→verify→settle path works.
- [ ] **ERC-8004 deploy:** deploy Identity + Reputation registries to Fuji (`contracts/README.md`).
      Record addresses in `.env` (`IDENTITY_REGISTRY_ADDRESS`, `REPUTATION_REGISTRY_ADDRESS`).
- [ ] **Seed demo agents:** register `good-data-co` (reputation ~85) and `sketchy-data-inc` (~12);
      give feedback so the reputation gate has real data.

### Known-risk knobs (flip these first if the smoke test fails)
1. `NETWORK` — try `eip155:43113` if `avalanche-fuji` is rejected by `/verify`.
2. web3j version in `smoke-test/pom.xml` — bump if `4.12.2` won't resolve.
3. Facilitator envelope field names (`paymentPayload`/`paymentRequirements`) and `maxAmountRequired`
   vs `amount` — confirm against x402.rs `/verify` schema (`GET /verify`).
4. Signature `v` byte (27/28 vs 0/1) — web3j should give 27/28; verify via `/verify`.

---

## Phase 1 — Treasury core (no chain). Built in `treasury/`; tests run via `scripts/treasury-mvn.sh`.
- **Stage 1 — skeleton + policy engine** ✅ (commit d281137)
  - [x] Spring Boot 3.3.5 / Java 21 project; `PaymentIntentState` with enforced transitions.
  - [x] Pure `PolicyEngine` (allowlist, reputation floor + tier scaling, per-tx cap, daily budget,
        velocity; deny-by-default, ordered). 14 tests green.
- **Stage 2 — persistence + ledger + state machine** ✅ (commit 36414d6)
  - [x] Postgres (Docker `treasury-pg`, `scripts/dev-db.sh`) + Flyway schema.
  - [x] `PaymentIntent` lifecycle + idempotency (unique key); double-entry ledger; daily-budget +
        velocity queries. 22 tests green against real Postgres.
- **Stage 3 — proxy + orchestration** ✅ (commit f85e057)
  - [x] `POST /proxy` + `X-Agent-Key` auth; `ReputationProvider` + `PaymentExecutor` interfaces with
        stubs; `TreasuryService` orchestrator; 29 tests + live HTTP demo verified. Virtual threads on.

## Phase 2 — Chain integration. Swap the Stage-3 stubs for real impls.
- [x] **`PaymentExecutor` (real)** ✅ (commit 7577bc8). `X402PaymentExecutor` (gated on
      `x402.enabled=true`) signs EIP-3009 + settles via facilitator. Live-verified: `/proxy` payment
      → real Fuji tx `0x762b0fe1…6ab6effe` (status 0x1, block 56239960); sketchy still 402-blocked
      with no chain call. Stub remains the default for offline tests.
- [x] **Deploy ERC-8004 to Fuji** ✅ (commit adb11b3). Lean compatible registries in `contracts/erc8004/`.
      Identity `0x313b59f6…8598`, Reputation `0x0455293B…5A3a`. Seeded: good agentId 1 (rep 85),
      sketchy agentId 2 (rep 12). Addresses in `.env`.
- [x] **`ReputationProvider` (real, web3j)** ✅ (commit c8ea2b7). `Erc8004ReputationProvider`
      (`erc8004.enabled=true`, @Primary): payee→agentId→`getSummary`, Caffeine 30s, fail-closed.
      Live-verified: good (chain rep 85)→SETTLED real tx `0x41f05847…` (status 0x1); sketchy (chain
      rep 12)→402, no settle. Both protocols real + on-chain together.
- [x] **Feedback writer (async)** ✅ (commit 36cadd1). `Erc8004FeedbackWriter` (@Async, @Primary):
      signs `giveFeedback(100)` for the payee post-settlement. Live-verified loop: paying good-data-co
      2× raised its on-chain reputation 85→90 (txs `0x90e48e8d…`, `0x6ce73dea…`); sketchy unchanged.
- [x] **Live dashboard** ✅ (commit cc2a876). `/api/dashboard/{agents,payments}` + static `index.html`
      (polls 2s): budget burn-down + color-coded feed (settled→Snowtrace link, denied→reason). Open
      `http://localhost:8090/`. Verified: feed shows SETTLED + both DENIED reasons; agent burn-down.
- [x] **Test/runtime DB isolation** ✅ (commit 60bafde). Tests use `treasury_test`; DataSeeder upserts
      the demo agent. (Fixed stale-row 401s that rolled back denials.)
- [ ] Reconciliation `@Scheduled` job: re-verify SETTLED/FAILED vs on-chain.
- [ ] Move the executor network call outside the DB transaction in `TreasuryService`.

## Phase 3 — Demo polish
- [ ] Dashboard: budget burn-down, payment feed, blocked-with-reason.
- [ ] Demo agent(s) pointed at the treasury proxy.
- [ ] Seed scenarios; rehearse the 4-beat demo (DESIGN.md §7); record backup video.

**Cut order under time pressure:** dashboard polish → reconciliation → feedback writer → velocity rule.
**Never cut:** idempotency, policy engine, the two live blocks (reputation + budget), one real settlement.

---

## Decisions log
- **2026-06-13** Dropped Mogami (hardcoded Base+Solana, no Fuji; AGPL). → x402.rs facilitator + web3j signing.
- **2026-06-13** Reputation convention: feedback as 0–100 int, `valueDecimals=0`; `count==0` → deny.
- **2026-06-13** Wire format x402 **v1** (`avalanche-fuji`); we control both ends of the demo.
- **2026-06-13** ERC-8004 deploy: skip vanity/CREATE2; plain UUPS proxies (canonical address not needed).

## Progress log
- **2026-06-13** Design doc + spike findings written; all design unknowns resolved via source-verified
  recon. Repo scaffolded; Phase-0 smoke-test bundle authored.
- **2026-06-13** Closed Mac→Linux dev loop (`ssh homelinux`; Docker-only builds). Facilitator running
  on Fuji. **Phase-0 gate PASSED** — first real x402/EIP-3009 settlement on Fuji, tx
  `0x81296747…b7230d95` (status 0x1, 0.01 USDC moved, gasless for payer). Fixed several
  `.env`/`--env-file` quoting traps and Linux GitHub auth along the way.
- **2026-06-13** **Phase 1 COMPLETE** (Stages 1-3). Treasury core in `treasury/`: pure policy engine,
  Postgres+Flyway persistence, double-entry ledger, idempotent intent state machine, `/proxy` +
  API-key auth, orchestration behind `ReputationProvider`/`PaymentExecutor` stubs. 29 tests green;
  live HTTP demo verified (settle / reputation-block / cap-block / idempotent replay). Next: Phase 2
  chain integration.
