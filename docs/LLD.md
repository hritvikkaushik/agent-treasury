# Low-Level Design — Agent Treasury

Class-, algorithm-, and schema-level detail. Above this: [ARCHITECTURE](./ARCHITECTURE.md),
[HLD](./HLD.md). Runtime sequences: [FLOWS](./FLOWS.md).

## 1. Domain

### `PaymentIntentState` (enum)
States: `REQUESTED, APPROVED, SIGNED, SETTLED, DENIED, FAILED`. Transition rules enforced by
`canTransitionTo`:

| From | Allowed to |
|------|-----------|
| REQUESTED | APPROVED, DENIED |
| APPROVED | SIGNED, DENIED |
| SIGNED | SETTLED, FAILED |
| FAILED | SETTLED (reconciliation) |
| SETTLED, DENIED | — (terminal) |

`isTerminal()` → SETTLED or DENIED.

### `PaymentIntent` (JPA `@Entity payment_intent`)
Fields: `id (UUID)`, `agentId`, `payee`, `asset`, `amountAtomic (long)`, `idempotencyKey`,
`state (@Enumerated STRING)`, `denialReason (@Enumerated STRING)`, `txHash`, `createdAt`, `updatedAt`.
Behavior (enforces the state machine):
- `transitionTo(target, now)` — throws `IllegalStateException` on an illegal transition.
- `deny(reason, now)` — → DENIED + records reason.
- `markSettled(txHash, now)` — → SETTLED + records tx hash.
Constructor starts in `REQUESTED` with `createdAt = updatedAt = now`.

### `AgentEntity` (JPA `@Entity agents`)
Holds policy columns + two `@ElementCollection` sets (`allowedMerchants`, `allowedAssets`, EAGER).
`toPolicy()` projects to the immutable `AgentPolicy`. Getters: `getId`, `getName`, `getApiKeyHash`.

### `JournalEntry` (JPA `@Entity journal_entry`)
`id (bigserial, IDENTITY)`, `intentId`, `account`, `debitAtomic`, `creditAtomic`, `createdAt`.
Factories: `debit(intentId, account, amount, now)`, `credit(...)`.

## 2. Policy (`policy`)

All money is in **atomic units** (USDC 6dp; 10000 = 0.01).

- `AgentPolicy(perTxCapAtomic, dailyBudgetAtomic, velocityPerMinute, allowedMerchants, allowedAssets,
  minReputation)` — record; address sets copied immutably.
- `PaymentContext(agentId, payee, asset, amountAtomic, spentTodayAtomic, recentPaymentCount,
  counterpartyReputation)` — reputation is `Integer` (null = unknown).
- `Decision(allowed, reason)` — `allow()` / `deny(reason)`.
- `DenialReason` — enum with a human `detail()`: `ASSET_NOT_ALLOWED, MERCHANT_NOT_ALLOWED,
  COUNTERPARTY_UNKNOWN, REPUTATION_BELOW_THRESHOLD, PER_TX_CAP_EXCEEDED, DAILY_BUDGET_EXHAUSTED,
  VELOCITY_LIMIT_EXCEEDED`.

### `PolicyEngine.evaluate(ctx, policy)` — pure, deny-by-default, ordered:
1. asset ∉ allowedAssets → `ASSET_NOT_ALLOWED`
2. payee ∉ allowedMerchants → `MERCHANT_NOT_ALLOWED`   *(addresses compared case-insensitively)*
3. reputation == null → `COUNTERPARTY_UNKNOWN`
4. reputation < `minReputation` → `REPUTATION_BELOW_THRESHOLD`
5. **tier multiplier**: reputation ≥ 80 → 100%, else 25%. `effectiveCap = perTxCap * pct/100`,
   `effectiveBudget = dailyBudget * pct/100`
6. amount > effectiveCap → `PER_TX_CAP_EXCEEDED`
7. spentToday + amount > effectiveBudget → `DAILY_BUDGET_EXHAUSTED`
8. recentPaymentCount ≥ velocityPerMinute → `VELOCITY_LIMIT_EXCEEDED`
9. else `allow()`

Constants: `FULL_TRUST_THRESHOLD = 80`, `REDUCED_LIMIT_PERCENT = 25`.

## 3. Intent lifecycle & idempotency (`intent.PaymentIntentService`)

- `getOrCreate(agentId, payee, asset, amount, idempotencyKey, now)` — `@Transactional`. Looks up by
  idempotency key; if absent, `saveAndFlush` a new intent. On `DataIntegrityViolationException` (a
  racing insert hit the unique constraint) it re-reads the winner. **Exactly-once.**
- `recentPaymentCount(agentId, now)` — `countByAgentIdAndCreatedAtAfter(agentId, now - 60s)`.
- `save(intent)`.

## 4. Ledger (`ledger`)

- `Accounts.budget(agentId)` = `"agent:{id}:budget"`; `Accounts.MERCHANT_PAYABLE` = `"merchant:payable"`.
- `LedgerService.recordPayment(intentId, agentId, amount, now)` — posts a balanced pair: debit budget,
  credit merchant-payable (`@Transactional`).
- `spentTodayAtomic(agentId, now)` — `sumDebitsSince(budget(agentId), now.truncatedTo(DAYS))`
  (start of UTC day).
- `JournalEntryRepository.sumDebitsSince` — `coalesce(sum(debitAtomic),0)` JPQL.

## 5. Orchestration (`service.TreasuryService.process`)  `@Transactional`

```
priorPayments = intents.recentPaymentCount(agentId, now)     // before create → excludes current
intent = intents.getOrCreate(...)                            // idempotent
if intent.state.isTerminal(): return PaymentResult.of(intent) // idempotent replay (no double-spend)
agent  = agents.findById(agentId)
ctx    = PaymentContext(amount, spentToday=ledger.spentToday(...), recentPaymentCount=priorPayments,
                        reputation=reputation.reputationOf(payee))
decision = policy.evaluate(ctx, agent.toPolicy())
if !allowed: intent.deny(reason); return
intent.transitionTo(APPROVED); intent.transitionTo(SIGNED)
exec = executor.execute(intent)
if exec.success: ledger.recordPayment(...); intent.markSettled(txHash); feedbackWriter.recordSuccessfulPayment(payee)
else:            intent.transitionTo(FAILED)
return PaymentResult.of(intents.save(intent))
```

## 6. Auth (`agent.AgentService`)

- `authenticate(apiKey)` → `agents.findByApiKeyHash(sha256Hex(apiKey))`.
- `sha256Hex(String)` — SHA-256 → lowercase hex. API keys are never stored in clear.

## 7. API (`api`)

### `ProxyController` — `POST /proxy`
Headers: `X-Agent-Key` (required), `Idempotency-Key` (optional; random UUID if absent).
Body: `PaymentRequest{ @NotBlank payee, @NotBlank asset, @NotNull @Positive amountAtomic }`.
Auth fails → 401. Maps result state → HTTP: SETTLED→200, DENIED→402, FAILED→502.
Response: `PaymentResult{ intentId, state, denialReason, denialDetail, txHash }`.

### `DashboardController`
- `GET /api/dashboard/agents` → `AgentView[]` (id, name, perTxCapAtomic, dailyBudgetAtomic,
  spentTodayAtomic, velocityPerMinute, minReputation).
- `GET /api/dashboard/payments` → `PaymentView[]` from `findTop50ByOrderByCreatedAtDesc()`
  (intentId, agentId, payee, amountAtomic, state, denialReason, denialDetail, txHash, createdAt).

## 8. Payment execution (`payment`)

`PaymentExecutor.execute(intent) → ExecutionResult{success, txHash, error}` (`ok`/`failed` factories).

### `X402PaymentExecutor` (real)
Constructor validates the treasury key, derives `fromAddress`. `execute`:
1. Build `Authorization(from=treasury, to=payee, value=amount, validAfter=now-600,
   validBefore=now+maxTimeoutSeconds, nonce=32 random bytes)`.
2. `Eip3009Signer.sign(auth, domain, privateKey)` → signature.
3. Build x402 v1 payload + requirements; `FacilitatorClient.settle(...)`.

### `Eip3009Signer.sign(...)` (static)
EIP-712 typed data via web3j `StructuredDataEncoder`:
- Domain `{ name:"USD Coin", version:"2", chainId:43113, verifyingContract: USDC }`.
- Type `TransferWithAuthorization(address from,address to,uint256 value,uint256 validAfter,
  uint256 validBefore,bytes32 nonce)`.
- `Sign.signMessage(digest, keyPair, needToHash=false)`; pack `r(32)||s(32)||v(1)`, **v ∈ {27,28}**.

### `FacilitatorClient.settle(payload, requirements)`
POST `/settle` with `{ x402Version:1, paymentPayload, paymentRequirements }`. `success` →
`ExecutionResult.ok(transaction)`; else `failed(errorReason — errorMessage)`. 10s connect / 30s read.

## 9. Reputation (`reputation`)

### `Erc8004ReputationProvider.reputationOf(counterparty)` — `@Primary` when enabled
Caffeine cache (30s TTL, max 1000). On miss `readChain`:
1. `agentIdOf(address)` via eth_call → if 0, unknown (empty).
2. `getSummary(agentId, [], "", "")` → `(count uint64, value int128, decimals uint8)`.
3. count 0 → unknown; else `score = value / 10^decimals`, clamp `[0,100]`.
Any error → empty (unknown). **Fail-closed** (unknown → policy denies).

### `Erc8004FeedbackWriter.recordSuccessfulPayment(payee)` — `@Async`, best-effort
1. `agentIdOf(payee)`; if 0, skip.
2. `giveFeedback(agentId, value=100, valueDecimals=0, "quality","","","", bytes32(0))`.
3. `RawTransactionManager.sendTransaction(gasPrice, 300000, reputationRegistry, data, 0)`; log tx hash.
Exceptions are logged, never propagated. (Concurrency note: rapid writes from the single treasury
wallet can collide on nonce — sequence them before production.)

web3j ABI calls use `FunctionEncoder`/`FunctionReturnDecoder` with `Uint256/Int128/Uint8/Uint64/
Address/Utf8String/DynamicArray/Bytes32`.

## 10. Reconciliation (`reconcile`)

- `ReceiptStatusProvider.statusOf(txHash) → {CONFIRMED, FAILED, NOT_FOUND}`.
  `Web3jReceiptStatusProvider`: `ethGetTransactionReceipt`; present + status `0x1` → CONFIRMED.
- `ReconciliationService.reconcile(now)` — fetch SETTLED intents in the last 24h
  (`findByStateAndCreatedAtAfter`); for each with a real 66-char tx hash, verify CONFIRMED, else WARN
  a mismatch. Returns `Result(checked, mismatched)`. `scheduled()` runs it on
  `initialDelay`/`fixedDelay` (default 30s/60s). Stub hashes (`0xstub…`) are skipped.

## 11. Seeding (`bootstrap.DataSeeder`) — `CommandLineRunner`

Upserts demo agent `agent-1` (key `demo-key-agent-1`, perTxCap 0.50, daily 5.00, velocity 5/min,
minRep 60, allowlist {good 0x6f40…, sketchy 0x0000…dEaD}, asset USDC) and sets stub reputations
(good 85, sketchy 12). Upsert (not create-if-absent) so the key/policy are always correct.

## 12. Schema (`db/migration/V1__init.sql`)

Tables per §[ARCHITECTURE](./ARCHITECTURE.md)#4. Indexes: `payment_intent(agent_id, created_at)`,
`journal_entry(account)`, `journal_entry(intent_id)`. Amounts `BIGINT`; timestamps `TIMESTAMPTZ`;
intent id `UUID`; `idempotency_key` `UNIQUE`.
