# ERC-8004 registries — Avalanche Fuji

The treasury needs two on-chain capabilities: **read** a counterparty's reputation (`getSummary`) and
**write** feedback after a payment (`giveFeedback`). Rather than deploy the full reference
implementation (Hardhat v3 + UUPS proxies + CREATE2 vanity addresses — heavy and fragile for a
sprint), we deploy **lean ERC-8004-*compatible* registries** in `erc8004/` that implement the same
verified signatures (`register`, `giveFeedback`, `getSummary`). See `SPIKE-FINDINGS.md` §4 for the
reference signatures we match. The full reference repo is https://github.com/erc-8004/erc-8004-contracts.

## What's here
```
erc8004/
  contracts/IdentityRegistry.sol     # register(uri) / registerFor(wallet,uri); agentIdOf, getAgentWallet
  contracts/ReputationRegistry.sol   # giveFeedback(...); getSummary(...) -> (count, avg, decimals)
  scripts/deploy.js                  # deploy both + register the 2 demo merchants + seed reputation
  hardhat.config.js                  # Fuji (chainId 43113); reads RPC_URL + TREASURY_PRIVATE_KEY
```

Convention: feedback is given as a 0-100 integer with `valueDecimals = 0`, so `getSummary` returns the
average directly as a 0-100 score. `count == 0` (no feedback) → unknown → the policy denies.

## Deploy to Fuji (Docker; host needs only Docker)
The deployer is the treasury wallet (`TREASURY_PRIVATE_KEY`), which holds test AVAX for gas. From the
repo root:
```bash
docker run --rm --env-file .env \
  -v "$PWD/contracts/erc8004":/app -w /app \
  -v "$HOME/.npm":/root/.npm \
  node:20 bash -lc "npm install --no-audit --no-fund && npx hardhat run scripts/deploy.js --network fuji"
```
Copy the printed addresses into the repo-root `.env`:
```
IDENTITY_REGISTRY_ADDRESS=0x...
REPUTATION_REGISTRY_ADDRESS=0x...
```

## Demo agents seeded by the deploy
- `good-data-co`  = `0x6f40…fbf` — feedback [90,85,80,85] → reputation ~85
- `sketchy-data-inc` = `0x0000…dEaD` — feedback [10,15,12] → reputation ~12

The treasury's web3j `ReputationProvider` (Phase 2b) maps payee address → agentId
(`IdentityRegistry.agentIdOf`) → `getSummary` → 0-100 score.
