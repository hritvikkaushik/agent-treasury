# ERC-8004 registries — deploy to Avalanche Fuji

We deploy the reference Identity + Reputation registries ourselves (they're not on Fuji officially).
Verified facts (toolchain, signatures, deploy flow) are in `SPIKE-FINDINGS.md` §4.

> We **don't vendor** the contracts repo here — clone it alongside and deploy from there, recording the
> resulting addresses back into this project's `.env`. (Keeping their git history out of ours.)

## Steps
```bash
# 1. Clone the reference contracts (CC0)
git clone https://github.com/erc-8004/erc-8004-contracts.git
cd erc-8004-contracts
npm install     # Hardhat v3, Solidity 0.8.24, OpenZeppelin upgradeable ^5.4.0

# 2. Configure Fuji (the repo already defines the `avalancheFuji` network)
export AVALANCHE_FUJI_RPC_URL=https://api.avax-test.network/ext/bc/C/rpc
export AVALANCHE_FUJI_PRIVATE_KEY=0x<your treasury key, funded with test AVAX>

# 3. Deploy. Canonical path (shared 0x8004… vanity address via CREATE2):
npx hardhat run scripts/deploy-vanity.ts --network avalancheFuji
#   Read VANITY_DEPLOYMENT_GUIDE.md first — needs a funded CREATE2 factory on Fuji.
#   SIMPLER alternative if the vanity path is fiddly: deploy plain UUPS proxies via
#   ignition/modules/ERC8004.ts, or a small ethers script. Canonical address is NOT required for us.
```

## Deploy order matters
Identity **first**, then Reputation (its initializer takes the Identity address):
- `IdentityRegistry.initialize()` — no args (owner = deployer).
- `ReputationRegistry.initialize(identityRegistryAddress)`.

Record the proxy addresses into `../.env`:
```
IDENTITY_REGISTRY_ADDRESS=0x...
REPUTATION_REGISTRY_ADDRESS=0x...
```

## Seed the demo agents (after deploy)
Register two merchant agents and seed reputation so the demo's reputation gate is real:
- `good-data-co`: `register(agentURI)`, then several `giveFeedback(agentId, 85, 0, …)` (value 85, decimals 0).
- `sketchy-data-inc`: `register(...)`, then `giveFeedback(agentId, 12, 0, …)`.

Reputation read for the policy gate: `getSummary(agentId, [], "", "")` → `(count, summaryValue, decimals)`;
score = `summaryValue / 10^decimals` (with our `decimals=0` convention this is just the average); treat
`count==0` as unknown → deny. See `SPIKE-FINDINGS.md` §4 for the exact signatures.

## web3j wrappers (for the treasury, Phase 2)
`register` is overloaded — generate Java wrappers from the JSON ABIs in the contracts repo's `/abis`
(not from Solidity) so selectors are correct:
```bash
web3j generate solidity -a <IdentityRegistry.json> -o <out> -p tech.treasury.chain.erc8004
```
