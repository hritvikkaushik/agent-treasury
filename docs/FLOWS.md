# Flows — Agent Treasury

Runtime sequences for the main paths. Components: **Agent**, **Proxy** (`ProxyController`),
**Treasury** (`TreasuryService`), **Policy** (`PolicyEngine`), **Rep** (`ReputationProvider`),
**Exec** (`PaymentExecutor`), **Fac** (x402.rs facilitator), **Chain** (Fuji), **DB**, **Feedback**
(`FeedbackWriter`). See [LLD](./LLD.md) for the code-level detail referenced here.

## 1. Successful payment (real / on-chain)

```
Agent → Proxy:    POST /proxy  (X-Agent-Key, {payee, asset, amountAtomic})
Proxy → Treasury: authenticate(key) → agentId; process(...)
Treasury → DB:    recentPaymentCount(); getOrCreate intent (REQUESTED)   [idempotent]
Treasury → Rep:   reputationOf(payee)  ──eth_call→ Chain (agentIdOf, getSummary)  ⇒ 85
Treasury → Policy: evaluate(ctx, policy) ⇒ ALLOW
Treasury:         intent → APPROVED → SIGNED
Treasury → Exec:  execute(intent)
   Exec:          sign EIP-3009 (treasury key)
   Exec → Fac:    POST /settle {paymentPayload, paymentRequirements}
   Fac → Chain:   transferWithAuthorization(...)  (facilitator pays gas)
   Fac → Exec:    {success:true, transaction: 0x…}
Treasury → DB:    ledger.recordPayment (debit budget / credit payable); intent.markSettled(tx) → SETTLED
Treasury → Feedback (async): recordSuccessfulPayment(payee)  ──tx→ Chain (giveFeedback 100)
Proxy → Agent:    200 {state: SETTLED, txHash: 0x…}
```

Key points: the agent never holds a key; settlement is gasless for the payer; feedback is fire-and-
forget (never blocks or fails the payment).

## 2. Denied payment (reputation / budget / cap / velocity)

```
Agent → Proxy → Treasury: process(...)
Treasury → DB:    getOrCreate intent (REQUESTED)
Treasury → Rep:   reputationOf(payee) ⇒ 12   (or unknown)
Treasury → Policy: evaluate ⇒ DENY(REPUTATION_BELOW_THRESHOLD)
Treasury → DB:    intent.deny(reason) → DENIED      ✗ no signing, no chain call
Proxy → Agent:    402 {state: DENIED, denialReason, denialDetail}
```

The guardrail short-circuits **before** the executor — a blocked payment touches no key and no chain.
Same shape for `PER_TX_CAP_EXCEEDED`, `DAILY_BUDGET_EXHAUSTED`, `VELOCITY_LIMIT_EXCEEDED`,
`MERCHANT_NOT_ALLOWED`, `ASSET_NOT_ALLOWED`, `COUNTERPARTY_UNKNOWN`.

## 3. Idempotent replay

```
process(..., idempotencyKey=K)  →  getOrCreate finds existing intent for K
   if intent.state.isTerminal():  return PaymentResult.of(intent)   // same outcome, charged once
```

A retried request with the same `Idempotency-Key` returns the original outcome; no second settlement,
no second ledger posting. Backed by the `payment_intent.idempotency_key` UNIQUE constraint.

## 4. Reputation read (gating)

```
reputationOf(addr):
  cache.get(addr):                              // Caffeine, 30s TTL
    agentId = Identity.agentIdOf(addr)          // eth_call
    if agentId == 0 → unknown (null)
    (count, value, dec) = Reputation.getSummary(agentId, [], "", "")   // eth_call
    if count == 0 → unknown (null)
    score = clamp(value / 10^dec, 0, 100)
  on any error → unknown (null)                 // fail-closed → policy denies
```

## 5. Feedback write (loop closes)

```
recordSuccessfulPayment(payee)  [async, after SETTLED]:
  agentId = Identity.agentIdOf(payee);  if 0 → skip
  giveFeedback(agentId, 100, 0, "quality", "", "", "", 0x0)  ──signed tx→ Chain
  log tx hash
```
Each paid interaction adds a positive feedback, so the counterparty's `getSummary` average rises over
time (e.g. 85 → 90). Future payments to it read the updated score.

## 6. Reconciliation (scheduled)

```
every interval (default 60s):
  settled = payment_intent where state=SETTLED and created_at > now-24h
  for each with a real 66-char txHash:
    status = receipts.statusOf(txHash)          // ethGetTransactionReceipt
    if status != CONFIRMED → WARN "RECONCILIATION MISMATCH"
  log "reconciliation: <checked> settled checked, <mismatched> mismatched"
```
Defense-in-depth: the ledger says SETTLED, but the chain is the arbiter. Stub hashes are skipped.

## 7. Offline mode (tests / no-chain demo)

With `x402.enabled=false` and `erc8004.enabled=false` (defaults): `StubPaymentExecutor` returns a fake
tx instantly, `StubReputationProvider` serves in-memory scores, `NoOpFeedbackWriter` does nothing,
reconciliation is absent. The orchestration, policy, ledger, idempotency, and API behave identically —
which is why the 38-test suite exercises the real flow without a chain.

## 8. Phase-0 smoke test (standalone, `smoke-test/`)

```
build Authorization → Eip3009Signer.sign → base64 X-PAYMENT (printed)
GET  /supported   (confirm eip155:43113)
POST /verify      → isValid:true
POST /settle      → success:true, transaction: 0x…   → Snowtrace link
```
This validated the signing/settlement path on Fuji before any treasury code (tx `0x81296747…`).
