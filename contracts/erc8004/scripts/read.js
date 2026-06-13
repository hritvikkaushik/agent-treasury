// Reads current on-chain reputation for the demo merchants. Addresses from env (REPUTATION/IDENTITY).
// docker run --rm --env-file .env -v "$PWD/contracts/erc8004":/app -w /app -v "$HOME/.npm":/root/.npm \
//   node:20 bash -lc "npm install --no-audit --no-fund >/dev/null && npx hardhat run scripts/read.js --network fuji"
const { ethers } = require("hardhat");

const MERCHANTS = [
  ["good-data-co", "0x6f409644a8a0b598284e8ca1a7562759f2189fbf"],
  ["sketchy-data-inc", "0x000000000000000000000000000000000000dEaD"],
];

async function main() {
  const id = await ethers.getContractAt("IdentityRegistry", process.env.IDENTITY_REGISTRY_ADDRESS);
  const rep = await ethers.getContractAt("ReputationRegistry", process.env.REPUTATION_REGISTRY_ADDRESS);
  for (const [name, addr] of MERCHANTS) {
    const agentId = await id.agentIdOf(addr);
    const [count, value, dec] = await rep.getSummary(agentId, [], "", "");
    console.log(`${name}: agentId=${agentId} reputation=${value} (from ${count} feedbacks, dec=${dec})`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
