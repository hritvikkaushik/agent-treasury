# Phase-0 smoke test

The go/no-go gate for the whole project. Signs one EIP-3009 USDC payment on Avalanche Fuji with web3j
and pushes it through the x402.rs facilitator (`/supported` → `/verify` → `/settle`). If this prints a
Snowtrace tx link, the signing + settlement path is proven and treasury work can build on it.

## What it validates
- The EIP-712 domain for Fuji USDC (`name:"USD Coin"`, `version:"2"`, chainId 43113).
- Signature packing (`r||s||v`, v ∈ {27,28}).
- The x402 v1 wire format + the facilitator request envelope.

`Eip3009Signer.java` is written to **port straight into the treasury** (Phase 2) — it's the reusable core.

## Prerequisites
- Java 21 + Maven 3.9+.
- The x402.rs facilitator running and reachable (`infra/facilitator/README.md`).
- A treasury wallet funded with **test USDC** (it's the payer) and a facilitator wallet with **test AVAX**.
- `PAY_TO` set to any address you control.

## Run
```bash
# from repo root
cp .env.example .env          # fill TREASURY_PRIVATE_KEY, PAY_TO, etc. (throwaway keys)
set -a; source .env; set +a
cd smoke-test
mvn -q compile exec:java
```

## Expected output (success)
```
signature: 0x...
X-PAYMENT: eyJ4NDAyVmVyc2lvbiI6MS...
--- GET /supported ---      (lists eip155:43113)
--- POST /verify ---        { "isValid": true, "payer": "0x..." }
--- POST /settle ---        { "success": true, "transaction": "0x...", ... }
SETTLED ✓  https://testnet.snowtrace.io/tx/0x...
```

## If it fails
| Symptom | Likely fix |
|---------|-----------|
| Can't reach facilitator | Start x402.rs; check `FACILITATOR_URL`. |
| `/verify` `isValid:false` | Set `NETWORK=eip155:43113`; confirm envelope/field names via `GET /verify`; recheck domain `name`/`version`. |
| `/settle` `success:false` | Treasury wallet has no test USDC, or facilitator wallet has no AVAX for gas. |
| web3j won't resolve | Bump `<web3j.version>` in `pom.xml` to the latest on Maven Central. |

> NOTE: this code was authored but not yet compiled/run against Fuji (no wallet in the origin env).
> First run is the real test — see RESUME-PLAN.md "Known-risk knobs".

## Files
- `SmokeTest.java` — orchestrates the run (config from env).
- `Eip3009Signer.java` — EIP-712 / EIP-3009 signing (**the reusable crux**).
- `X402Payment.java` — builds the payload + base64 `X-PAYMENT` header.
- `FacilitatorClient.java` — `/verify`, `/settle`, `/supported` calls.
