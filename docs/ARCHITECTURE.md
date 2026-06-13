# Architecture — Agent Treasury

Module structure, the interface seams, bean wiring, the data model, and how the same code runs offline
or on-chain. For altitude above this see [HLD](./HLD.md); for class-level detail see [LLD](./LLD.md).

## 1. Module / package layout (`treasury/src/main/java/tech/treasury`)

```
TreasuryApplication           @SpringBootApplication @EnableAsync @EnableScheduling
                              @EnableConfigurationProperties(X402Properties, Erc8004Properties)

api/        ProxyController          POST /proxy  (agent ingress, X-Agent-Key auth)
            DashboardController      GET /api/dashboard/{agents,payments}
            PaymentRequest           request DTO (payee, asset, amountAtomic)
            PaymentResult            response DTO (intentId, state, denialReason, denialDetail, txHash)
            AgentView, PaymentView   dashboard DTOs
agent/      AgentService             API-key auth (SHA-256 hash lookup)
service/    TreasuryService          orchestrator (@Transactional)
policy/     PolicyEngine             pure evaluation
            AgentPolicy, PaymentContext, Decision, DenialReason
intent/     PaymentIntentService     idempotent getOrCreate, velocity count
ledger/     LedgerService, Accounts  double-entry postings, daily-spend
payment/    PaymentExecutor          interface (+ ExecutionResult)
            StubPaymentExecutor      default (x402.enabled=false)
            X402PaymentExecutor      real (x402.enabled=true)
            x402/ Eip3009Signer, FacilitatorClient, X402Properties
reputation/ ReputationProvider       interface
            StubReputationProvider   default (in-memory)
            Erc8004ReputationProvider real, @Primary (erc8004.enabled=true)
            FeedbackWriter           interface
            NoOpFeedbackWriter       default
            Erc8004FeedbackWriter    real, @Async, @Primary (erc8004.enabled=true)
            Erc8004Properties
reconcile/  ReceiptStatusProvider    interface (+ TxStatus)
            Web3jReceiptStatusProvider  real (x402.enabled=true)
            ReconciliationService    @Scheduled (x402.enabled=true)
domain/     PaymentIntentState (enum+transitions), PaymentIntent, AgentEntity, JournalEntry
repo/       AgentRepository, PaymentIntentRepository, JournalEntryRepository
bootstrap/  DataSeeder               CommandLineRunner — upserts demo agent + stub reputations
```

## 2. The interface seams (chain isolation)

The chain layer sits behind four interfaces. Each has a default offline bean and a real on-chain bean
selected by a config flag. This keeps tests offline and lets the *same* orchestration code run live.

| Interface | Default bean | Real bean | Activation |
|-----------|-------------|-----------|------------|
| `PaymentExecutor` | `StubPaymentExecutor` | `X402PaymentExecutor` | `x402.enabled` |
| `ReputationProvider` | `StubReputationProvider` | `Erc8004ReputationProvider` (`@Primary`) | `erc8004.enabled` |
| `FeedbackWriter` | `NoOpFeedbackWriter` | `Erc8004FeedbackWriter` (`@Primary`, `@Async`) | `erc8004.enabled` |
| `ReceiptStatusProvider` | — (absent) | `Web3jReceiptStatusProvider` | `x402.enabled` |

Wiring patterns:
- **Executor:** mutually-exclusive `@ConditionalOnProperty` (`havingValue=false matchIfMissing=true`
  for the stub; `true` for real) — exactly one bean.
- **Reputation / feedback:** the stub is always a bean; the real one is `@ConditionalOnProperty
  havingValue=true` + `@Primary`, so it supersedes the stub when enabled (and `DataSeeder`, which
  injects the concrete stub, still wires).
- **Reconciliation:** service + receipt provider exist only when `x402.enabled=true` (they need a
  chain); the reconciliation *logic* is unit-tested directly via a fake `ReceiptStatusProvider`.

## 3. Configuration model

`application.yml` binds two `@ConfigurationProperties` records and reads everything from env with
sensible Fuji defaults:

- `x402.*` → `X402Properties` (enabled, facilitatorUrl, network, chainId, asset, usdcDomainName/Version,
  maxTimeoutSeconds, treasuryPrivateKey)
- `erc8004.*` → `Erc8004Properties` (enabled, identityRegistry, reputationRegistry, rpcUrl, chainId,
  treasuryPrivateKey)
- `reconciliation.*` → initial-delay-ms, interval-ms
- `spring.datasource.*`, `spring.flyway`, `server.port: 8090`, virtual threads on

Env precedence lets a stub run (no flags) and a real run (`X402_ENABLED=true ERC8004_ENABLED=true`)
share one image. Full reference in [USAGE](./USAGE.md).

## 4. Data model

```
agents (id PK, name, api_key_hash, per_tx_cap_atomic, daily_budget_atomic,
        velocity_per_minute, min_reputation, created_at)
agent_allowed_merchants (agent_id FK, merchant)        -- @ElementCollection
agent_allowed_assets     (agent_id FK, asset)          -- @ElementCollection
payment_intent (id PK uuid, agent_id FK, payee, asset, amount_atomic,
        idempotency_key UNIQUE, state, denial_reason, tx_hash, created_at, updated_at)
journal_entry (id PK bigserial, intent_id FK, account, debit_atomic, credit_atomic, created_at)
```

- `idempotency_key UNIQUE` is the DB-level guarantee behind exactly-once payment.
- Ledger accounts: `agent:{id}:budget` (debited on spend) and `merchant:payable` (credited).
- Schema is owned by Flyway (`V1__init.sql`); Hibernate `ddl-auto: none`.

## 5. Runtime topology (dev / demo)

```
Linux host (Docker only)
 ├─ treasury-pg         postgres:16            :5432   (DBs: treasury, treasury_test)
 ├─ x402-facilitator    ghcr.io/x402-rs/...     :8080   config.json → eip155:43113
 └─ treasury-app        maven:…-temurin-21      :8090   --network host, --env-file .env
                         (mvn spring-boot:run; reaches pg + facilitator on localhost)
External: Avalanche Fuji RPC, ERC-8004 registries, USDC contract.
```

Builds run inside containers (the host has no Java/Maven/Node). See `scripts/treasury-mvn.sh`,
`scripts/dev-db.sh`, `infra/facilitator/`.

## 6. Request handling & concurrency

`POST /proxy` runs on Tomcat with Spring's virtual-threads enabled — appropriate because each request
blocks on policy evaluation, chain reads (reputation), and settlement (facilitator round-trip).
`TreasuryService.process` is `@Transactional`; feedback writing is dispatched `@Async` off the request
path. (Known polish item: the executor's network call currently runs inside the DB transaction.)
