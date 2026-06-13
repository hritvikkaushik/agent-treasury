# User Guide — Agent Treasury

A guide for **agent developers**: how to pay through the treasury, what the guardrails do, and how to
read the outcomes. Operational/deploy details are in [USAGE](./USAGE.md).

## What is the treasury?

It's a payment gateway your AI agent calls instead of holding a crypto wallet itself. Your agent sends
a simple HTTP request saying "pay this merchant this much"; the treasury checks it against your
spending policy and the merchant's on-chain reputation, and — only if it passes — signs and settles a
real USDC payment on Avalanche. **Your agent never holds a private key.** If something goes wrong (a
prompt injection, a bug, a bad merchant), the worst case is bounded by your policy.

## Getting access

You authenticate with an **API key** sent in the `X-Agent-Key` header. In this build a demo agent is
pre-provisioned:

- API key: `demo-key-agent-1`
- Policy: max **$0.50** per payment, **$5.00** per day, **5 payments/minute**, only **USDC**, only
  whitelisted merchants, and counterparties must have reputation **≥ 60**.

(Keys are stored only as hashes; the treasury never sees your key in clear after lookup.)

## Making a payment

`POST /proxy` with the merchant address, the asset (USDC), and the amount in **atomic units**
(USDC has 6 decimals, so `100000` = $0.10):

```bash
curl -s -X POST http://localhost:8090/proxy \
  -H "X-Agent-Key: demo-key-agent-1" \
  -H "Content-Type: application/json" \
  -d '{"payee":"0x6f409644a8a0b598284e8ca1a7562759f2189fbf",
       "asset":"0x5425890298aed601595a70AB815c96711a31Bc65",
       "amountAtomic":100000}'
```

Successful response:
```json
{ "intentId":"…", "state":"SETTLED", "txHash":"0x…", "denialReason":null }
```
`txHash` is a real Avalanche Fuji transaction — view it at `https://testnet.snowtrace.io/tx/<txHash>`.

### Safe retries (idempotency)

Add an `Idempotency-Key` header with a stable id for the logical payment:
```
-H "Idempotency-Key: order-4711"
```
If you retry with the same key, you get the **same** outcome back and are **charged once**. Without
the header, each request is treated as a new payment.

## Understanding outcomes

| HTTP | `state` | Meaning |
|------|---------|---------|
| 200 | `SETTLED` | Paid; `txHash` is the on-chain transfer |
| 402 | `DENIED` | Blocked by policy — see `denialReason` |
| 502 | `FAILED` | Passed policy but settlement failed downstream (retry later) |
| 401 | — | Missing/invalid `X-Agent-Key` |
| 400 | — | Malformed request body |

### Denial reasons (`denialReason`)

| Reason | What happened | What to do |
|--------|---------------|-----------|
| `MERCHANT_NOT_ALLOWED` | payee isn't on your allowlist | use an approved merchant |
| `ASSET_NOT_ALLOWED` | asset isn't permitted | pay in USDC |
| `COUNTERPARTY_UNKNOWN` | merchant has no on-chain identity/reputation | pick a registered merchant |
| `REPUTATION_BELOW_THRESHOLD` | merchant's reputation is below your floor | pick a higher-rep merchant |
| `PER_TX_CAP_EXCEEDED` | amount exceeds your per-payment cap | split or lower the amount |
| `DAILY_BUDGET_EXHAUSTED` | would exceed today's budget | wait for the daily reset (UTC) |
| `VELOCITY_LIMIT_EXCEEDED` | too many payments this minute | slow down |

A denied payment moves **no funds** and makes **no on-chain call** — the check happens before signing.

## How reputation affects you

The treasury reads each merchant's reputation from the on-chain ERC-8004 registry:
- **≥ 80** → your full limits apply.
- **between your floor and 80** → reduced limits (25% of cap and daily budget) — lower-trust
  counterparties get less rope.
- **below your floor, or unregistered** → denied.

After a successful payment, the treasury writes positive feedback on-chain, so reliable merchants you
pay accrue reputation over time.

## Watching activity

Open the dashboard at **`http://localhost:8090/`**: your daily-budget burn-down, a live feed of
payments (settled with a Snowtrace link; denied with the reason highlighted), and your policy limits.

## FAQ

- **Do I need crypto / a wallet?** No. You hold an API key; the treasury custodies funds and keys.
- **What's an "atomic unit"?** The smallest USDC unit (6 decimals). `1000000` = $1.00.
- **Can I overspend?** No — the policy is deny-by-default and enforced server-side on every request.
- **Is the payment real?** In on-chain mode, yes — a real USDC transfer on Fuji with a tx hash. In
  offline mode the treasury returns a simulated result for development.
