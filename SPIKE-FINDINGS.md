# Spike Findings — verified facts for the build

> Reconnaissance completed 2026-06-13. Every fact below is sourced from primary docs or source code
> (cited inline). The **live-execution** steps (faucet funding, contract deploy, end-to-end settle)
> still require an interactive wallet — see §6. This doc is the build reference; `DESIGN.md` is the plan.

---

## 0. Headline decisions (what the spikes changed)

1. **Drop Mogami.** It is hardcoded to Base + Solana (whitelist in `x402-commons/Networks.java`);
   no Avalanche/Fuji, no chainId config. Adding Fuji means forking an AGPL-3.0 lib. Not worth it.
2. **Facilitator = x402.rs**, self-hosted via Docker, pointed at Fuji. Source-confirmed Fuji support
   (`eip155:43113`, Fuji USDC baked into its `networks.rs`).
3. **Client signing = web3j ourselves** (EIP-712 over EIP-3009). No third-party client SDK. This is
   now the primary path, not a fallback.
4. **Wire format = x402 "v1"** (`network: "avalanche-fuji"`, `maxAmountRequired`). It's the format
   the Avalanche academy + deployed facilitators use. x402.rs supports both v1 and v2; we control
   both ends of the demo, so we standardize on v1.

Net effect: the stack is **100% our Java/Spring Boot + web3j + a Rust facilitator process + Hardhat
for the contracts**. No Mogami. Fewer unknowns, cleaner licensing, stronger story.

---

## 1. Avalanche Fuji — concrete facts (all verified)

| Fact | Value |
|------|-------|
| Chain | Avalanche Fuji C-Chain (EVM) |
| chainId | **43113** (CAIP-2 `eip155:43113`) |
| RPC URL | `https://api.avax-test.network/ext/bc/C/rpc` |
| Explorer | `https://testnet.snowtrace.io` |
| Testnet USDC | `0x5425890298aed601595a70AB815c96711a31Bc65` (native Circle USDC, implements EIP-3009) |
| USDC decimals | 6 (amounts are atomic strings, e.g. `"10000"` = 0.01 USDC) |
| USDC EIP-712 domain `name` | **"USD Coin"** (verified via on-chain `name()` call) |
| USDC EIP-712 domain `version` | **"2"** (verified via on-chain `version()` call) |
| Test AVAX faucet | `build.avax.network/console/primary-network/faucet` (Core Wallet console) |
| Test USDC faucet | `https://faucet.circle.com/` → select "Avalanche Fuji" |

Source: [Avalanche x402 academy — network setup](https://build.avax.network/academy/blockchain/x402-payment-infrastructure/04-x402-on-avalanche/02-network-setup); USDC domain values verified by direct `eth_call` against the contract.

> The two values that silently break EIP-712 signatures — domain `name` and `version` — were verified
> against the live contract, not just docs: `name()` → "USD Coin", `version()` → "2".

---

## 2. x402.rs facilitator

**Repo:** https://github.com/x402-rs/x402-rs · **Image:** `ghcr.io/x402-rs/x402-facilitator`

### Run it (Docker)
```bash
docker run -v $(pwd)/config.json:/app/config.json -p 8080:8080 ghcr.io/x402-rs/x402-facilitator
```
Env vars the binary reads: `HOST` (default `0.0.0.0`), `PORT` (default `8080`), `CONFIG` (default
`config.json`), plus optional OTEL vars. **Everything else — RPC, signer key, networks — lives in
`config.json`**, with `$VAR` placeholders interpolated from the environment.

### `config.json` for Fuji (adapt from `config.json.example`)
```json
{
  "port": 8080,
  "host": "0.0.0.0",
  "chains": {
    "eip155:43113": {
      "eip1559": true,
      "signers": ["$FACILITATOR_PRIVATE_KEY"],
      "rpc": [
        { "http": "https://api.avax-test.network/ext/bc/C/rpc", "rate_limit": 50 }
      ]
    }
  },
  "schemes": [
    { "id": "v1-eip155-exact", "chains": "eip155:*" },
    { "id": "v2-eip155-exact", "chains": "eip155:*" }
  ]
}
```
The facilitator's signer wallet (`$FACILITATOR_PRIVATE_KEY`) **must hold test AVAX** — it submits the
settlement tx and pays gas (the x402 gasless model: payer signs, facilitator pays gas).

### HTTP API
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/verify` | POST | Verify a payment payload (no chain write) |
| `/settle` | POST | Settle on-chain (calls `transferWithAuthorization`) |
| `/supported` | GET | List supported scheme × chain pairs |
| `/health` | GET | Health check |

`POST /verify` and `POST /settle` body (camelCase, verified from `x402-types/src/proto`):
```json
{ "x402Version": 1, "paymentPayload": { ... }, "paymentRequirements": { ... } }
```
`/verify` response: `{ "isValid": true, "payer": "0x..." }` (or `isValid:false` + `invalidReason`).
`/settle` response: `{ "success": true, "payer": "0x...", "transaction": "0x...", "network": "..." }`
(or `success:false` + `errorReason`).

> A hosted `https://facilitator.x402.rs/` exists and advertises "Avalanche", but it was unclear
> whether it enables **Fuji testnet** specifically (could be C-Chain mainnet only). **Verify by
> `GET https://facilitator.x402.rs/supported` and looking for `eip155:43113`. If absent, self-host**
> (the config above). Self-hosting is the safe default for a controlled demo anyway.

---

## 3. x402 wire formats (v1 — what we implement)

### 402 response body (the merchant returns this)
```json
{
  "x402Version": 1,
  "accepts": [{
    "scheme": "exact",
    "network": "avalanche-fuji",
    "maxAmountRequired": "10000",
    "resource": "/api/premium-data",
    "description": "Real-time market analysis",
    "payTo": "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb",
    "asset": "0x5425890298aed601595a70AB815c96711a31Bc65",
    "maxTimeoutSeconds": 60
  }],
  "error": "X-PAYMENT header is required"
}
```
Optional fields also in spec: `mimeType`, `outputSchema`, **`extra`** (`{name, version}` for the
EIP-712 domain — read it if the server sends it, else default to the verified Fuji values).

### X-PAYMENT request header
`X-PAYMENT: <base64(UTF-8 JSON)>` where the JSON is:
```json
{
  "x402Version": 1,
  "scheme": "exact",
  "network": "avalanche-fuji",
  "payload": {
    "signature": "0x<r||s||v, 65 bytes>",
    "authorization": {
      "from": "0x<payer>",
      "to": "0x<payTo>",
      "value": "10000",
      "validAfter": "<unix-secs string>",
      "validBefore": "<unix-secs string>",
      "nonce": "0x<32 random bytes>"
    }
  }
}
```

### EIP-712 typed data to sign (the crux of the web3j path)
**Domain:** `{ name: "USD Coin", version: "2", chainId: 43113, verifyingContract: "0x5425890298aed601595a70AB815c96711a31Bc65" }`

**Type:**
```
TransferWithAuthorization(
  address from, address to, uint256 value,
  uint256 validAfter, uint256 validBefore, bytes32 nonce
)
```
TYPEHASH (from EIP-3009): `0x7c7c6cdb67a18743f49ec6fa9b35f50d52ed05cbed4cc592e13b44501c1a2267`

**web3j specifics:**
- Sign the typed data, then pack signature as `r(32) || s(32) || v(1)`.
- **`v` must be 27/28**, not 0/1 (USDC FiatTokenV2 ECDSA expects 27/28). web3j's `Sign` gives 27/28.
- `nonce` = 32 random bytes (arbitrary, not sequential).
- `validAfter` = now − small buffer; `validBefore` = now + `maxTimeoutSeconds` (or less).
- `to` must equal `payTo`; `value` must satisfy `maxAmountRequired`.

### X-PAYMENT-RESPONSE header (settlement result, on the 200)
`base64(JSON)`: `{ "success": true, "transaction": "0x<hash>", "network": "avalanche-fuji", "payer": "0x...", "errorReason": null }`

Sources: [Avalanche academy — HTTP 402](https://build.avax.network/academy/blockchain/x402-payment-infrastructure/03-technical-architecture/02-http-payment-required), [X-PAYMENT header](https://build.avax.network/academy/blockchain/x402-payment-infrastructure/03-technical-architecture/03-x-payment-header), [EIP-3009](https://eips.ethereum.org/EIPS/eip-3009).

> **One thing to validate against the live facilitator before trusting it:** the exact `v` packing
> (27/28). Sign one payment and hit `POST /verify` — if `isValid:true`, the signature path is correct.
> This is the single highest-risk detail in the whole build (a wrong domain or `v` → silently
> invalid signature). Do it in the Phase-0 spike.

---

## 4. ERC-8004 registries

**Repo:** https://github.com/erc-8004/erc-8004-contracts (License CC0) · **Spec:** [EIP-8004](https://eips.ethereum.org/EIPS/eip-8004)

### Toolchain
- **Hardhat v3** (TypeScript config), **not Foundry**. Solidity **0.8.24**, evmVersion `shanghai`,
  optimizer 200, `viaIR: true`. OpenZeppelin upgradeable `^5.4.0`, **UUPS proxies**.
- **Fuji is already configured** in `hardhat.config.ts` (network key `avalancheFuji`, chainId 43113,
  Snowtrace explorer). Env vars: **`AVALANCHE_FUJI_RPC_URL`**, **`AVALANCHE_FUJI_PRIVATE_KEY`**.

### Deploy to Fuji
The repo's canonical path is a CREATE2 "vanity" deploy (all chains share `0x8004…` addresses):
```bash
npm install
npx hardhat run scripts/deploy-vanity.ts --network avalancheFuji
```
…but the vanity path needs precomputed salts + a funded CREATE2 factory on Fuji (`scripts/deploy-create2-factory.ts`).
**Simpler for our purposes** (we don't need the canonical address): deploy standard UUPS proxies
ourselves with a small ethers/Hardhat script, or use the in-repo `ignition/modules/ERC8004.ts`.
Read `VANITY_DEPLOYMENT_GUIDE.md` + `UPGRADEABLE_IMPLEMENTATION.md` first.

**Deploy order matters:** Identity first, then Reputation. Reputation's initializer takes Identity's
address:
- `IdentityRegistry.initialize()` — no args (placeholder zero address; owner = deployer).
- `ReputationRegistry.initialize(identityRegistryAddress)`.
- (ValidationRegistry also takes the Identity address — but it's out of scope for us.)

### Identity Registry — key signatures (Solidity ^0.8.20, ERC721 + UUPS)
```solidity
function register() external returns (uint256 agentId)
function register(string memory agentURI) external returns (uint256 agentId)
function register(string memory agentURI, MetadataEntry[] memory metadata) external returns (uint256 agentId)
function getAgentWallet(uint256 agentId) external view returns (address)
function setAgentWallet(uint256 agentId, address newWallet, uint256 deadline, bytes calldata signature) external
function getMetadata(uint256 agentId, string memory metadataKey) external view returns (bytes memory)
function setMetadata(uint256 agentId, string memory metadataKey, bytes memory metadataValue) external
function setAgentURI(uint256 agentId, string calldata newURI) external
// struct MetadataEntry { string metadataKey; bytes metadataValue; }
```
`register` is overloaded — **generate web3j wrappers from the JSON ABI**, not the Solidity, to get
correct selectors.

### Reputation Registry — key signatures
```solidity
function giveFeedback(uint256 agentId, int128 value, uint8 valueDecimals,
    string tag1, string tag2, string endpoint,
    string feedbackURI, bytes32 feedbackHash) external

function getSummary(uint256 agentId, address[] clientAddresses, string tag1, string tag2)
    external view returns (uint64 count, int128 summaryValue, uint8 summaryValueDecimals)

function readFeedback(uint256 agentId, address clientAddress, uint64 feedbackIndex)
    external view returns (int128 value, uint8 valueDecimals, string tag1, string tag2, bool isRevoked)

function revokeFeedback(uint256 agentId, uint64 feedbackIndex) external
```

### `getSummary` semantics → our 0–100 normalization
- `count` = number of non-revoked matching feedback entries.
- `summaryValue` = the **AVERAGE** (not sum), computed in 18-decimal WAD internally then scaled.
- `summaryValueDecimals` = the **mode** of the matched entries' decimals.
- Real average = `summaryValue / 10^summaryValueDecimals`.
- **No 0–100 range is enforced on-chain** — the scale is an off-chain convention. `giveFeedback`
  only enforces `valueDecimals <= 18` and `|value| <= 1e38`.

**Our convention (decision):** we write feedback as an integer score **0–100 with `valueDecimals = 0`**.
Then `getSummary` returns the average score directly; if `count == 0` (unknown agent) → treat as
**unknown → deny** per the policy tier. Clamp to `[0,100]` defensively. This keeps the demo legible
(the dashboard shows a clean 0–100 score) and the normalizer trivial.

### Agent Registration File (tokenURI → JSON)
MUST: `type`, `name`, `description`, `image`, `services[]`, `registrations[]` (each
`{agentId, agentRegistry}` where `agentRegistry` = `eip155:43113:<IdentityRegistryAddr>`).
SHOULD: `x402Support` (bool), `active` (bool). MAY: `supportedTrust`. For the demo we serve this JSON
from the treasury over HTTPS (no IPFS needed).

Sources: `contracts/IdentityRegistryUpgradeable.sol`, `contracts/ReputationRegistryUpgradeable.sol`,
`hardhat.config.ts`, `scripts/deploy-vanity.ts`, `ERC8004SPEC.md` in the [repo](https://github.com/erc-8004/erc-8004-contracts).

---

## 5. Open items still to confirm (cheap, do during Phase 0)

1. **`/supported` on hosted x402.rs** — does it list `eip155:43113`? If yes, skip self-hosting for v0.
2. **Signature `v` packing** — sign one payment, POST to `/verify`, expect `isValid:true`.
3. **CREATE2 factory on Fuji** — only relevant if we use the vanity deploy; we're likely skipping it.
4. **getSummary rounding** — open `ReputationRegistryUpgradeable.sol` and confirm the exact rounding
   before finalizing the normalizer (low risk given our `valueDecimals=0` convention).

---

## 6. Live-execution checklist (needs YOUR wallet — I can't do these headless)

These are mechanical once you run them. Suggested order:

1. **Create a dev wallet** (e.g. a fresh MetaMask/Core account) and export its private key for `.env`.
   Use a throwaway key — it goes in local config.
2. **Fund it with test AVAX** — `build.avax.network/console/primary-network/faucet` (Core console).
   Fund **two** wallets: the treasury omnibus wallet and the facilitator signer wallet (both need AVAX
   for gas — treasury for ERC-8004 writes + deploys, facilitator for settlement).
3. **Fund the treasury wallet with test USDC** — `https://faucet.circle.com/` → Avalanche Fuji.
4. **Deploy ERC-8004 to Fuji** — clone the contracts repo, `npm install`, set `AVALANCHE_FUJI_RPC_URL`
   + `AVALANCHE_FUJI_PRIVATE_KEY`, run the deploy (vanity script or a simple UUPS script). Record the
   Identity + Reputation proxy addresses.
5. **Register two merchant agents** + seed reputation (give `good-data-co` several high scores,
   `sketchy-data-inc` low ones) so the demo's reputation block is real.
6. **Run x402.rs** — `docker run … ghcr.io/x402-rs/x402-facilitator` with the Fuji `config.json` and
   the facilitator signer key. Hit `/supported`, confirm `eip155:43113`.
7. **Smoke test** — sign one EIP-3009 payment with web3j, `POST /verify`, then `POST /settle`, confirm
   the tx on Snowtrace. This proves the whole chain works before we build the treasury on top.

> I can write the deploy script, the `config.json`, the web3j signing code, and a smoke-test harness
> for you to run — say the word and I'll generate them. The faucet/captcha steps are the only truly
> manual part.
