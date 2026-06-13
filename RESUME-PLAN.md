# Resume Plan — Agent Treasury

> **Purpose:** this is the live state machine for the project. An agent (or human) picking up the work
> should read this top-to-bottom, do the **Next action**, and then **update the STATE block + Progress
> log** below before stopping. Treat checkboxes as authoritative.

---

## STATE (update this block every session)

- **Phase:** 0 (Spikes / foundation) — reconnaissance done, live execution pending.
- **Last completed:** Wrote Phase-0 smoke-test bundle (`smoke-test/`), facilitator config
  (`infra/facilitator/`), ERC-8004 deploy notes (`contracts/`), repo scaffolding (git, .gitignore,
  .env.example, CLAUDE.md, README, .claude template). Resolved all design unknowns (see SPIKE-FINDINGS).
- **Next action:** On the Linux box — fund wallets from faucets, run the x402.rs facilitator against
  Fuji, then run the smoke test (`cd smoke-test && mvn -q compile exec:java`) and confirm a real
  settlement tx on Snowtrace. This validates the EIP-712 signing path before any treasury code.
- **Blockers:** Live steps need a funded Fuji wallet (faucet captcha = manual) and Docker (Linux).
  The smoke-test Java code has **not been compiled/run yet** (no Fuji wallet in this environment) —
  first run may surface a web3j version bump or a `network`-string tweak (see Known-risk knobs below).

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
- [ ] **Facilitator:** run x402.rs against Fuji (`infra/facilitator/README.md`); `GET /supported`
      shows `eip155:43113`.
- [ ] **Smoke test:** `cd smoke-test && mvn -q compile exec:java`. Expect `/verify` → `isValid:true`,
      `/settle` → `success:true` + a tx hash. Confirm on `https://testnet.snowtrace.io`.
      → **This is the go/no-go gate for the whole approach.**
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

## Phase 1 — Treasury core (no chain; build in parallel with Phase-0 live steps)
- [ ] Spring Boot 3 / Java 21 skeleton (Maven), Postgres, Flyway. Seed agents + policies.
- [ ] Proxy endpoint (`POST /proxy`) + `X-Agent-Key` auth. Virtual threads.
- [ ] `PaymentIntent` state machine (`REQUESTED→APPROVED→SIGNED→SETTLED` / `DENIED` / `FAILED`) with
      idempotency key (unique constraint).
- [ ] Policy engine as a pure function `evaluate(intent, policy, reputation) → Decision`. Unit tests:
      per-tx cap, daily budget, allowlist, velocity, reputation tier.
- [ ] Double-entry ledger + daily-budget computation.

## Phase 2 — Chain integration
- [ ] Port `Eip3009Signer` from smoke-test; wire 402 → sign → `X-PAYMENT` → facilitator settle.
- [ ] Reputation oracle: web3j read of `getSummary` + Caffeine cache (30s TTL) + tier rule.
- [ ] Feedback writer: async `giveFeedback` post-settlement.
- [ ] Reconciliation `@Scheduled` job: re-verify SETTLED/FAILED vs on-chain.

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
  recon. Repo scaffolded; Phase-0 smoke-test bundle authored (unrun — needs Fuji wallet). Next:
  live Phase-0 execution on the Linux box.
