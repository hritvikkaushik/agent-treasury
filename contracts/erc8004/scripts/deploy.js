// Deploys the registries to Fuji, registers the two demo merchants, and seeds reputation.
// Run: docker run --rm --env-file .env -v "$PWD/contracts/erc8004":/app -w /app \
//        -v "$HOME/.npm":/root/.npm node:20 \
//        bash -lc "npm install --no-audit --no-fund && npx hardhat run scripts/deploy.js --network fuji"
const { ethers } = require("hardhat");

const GOOD = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";    // good-data-co
const SKETCHY = "0x000000000000000000000000000000000000dEaD"; // sketchy-data-inc

async function seedFeedback(rep, agentId, scores) {
  for (const v of scores) {
    const tx = await rep.giveFeedback(agentId, v, 0, "quality", "", "", "", ethers.ZeroHash);
    await tx.wait();
  }
}

async function main() {
  const [deployer] = await ethers.getSigners();
  console.log("deployer:", deployer.address);

  const Identity = await ethers.getContractFactory("IdentityRegistry");
  const identity = await Identity.deploy();
  await identity.waitForDeployment();
  const identityAddr = await identity.getAddress();

  const Reputation = await ethers.getContractFactory("ReputationRegistry");
  const reputation = await Reputation.deploy(identityAddr);
  await reputation.waitForDeployment();
  const reputationAddr = await reputation.getAddress();

  await (await identity.registerFor(GOOD, "ipfs://good-data-co")).wait();
  await (await identity.registerFor(SKETCHY, "ipfs://sketchy-data-inc")).wait();
  const goodId = await identity.agentIdOf(GOOD);
  const sketchyId = await identity.agentIdOf(SKETCHY);

  await seedFeedback(reputation, goodId, [90, 85, 80, 85]);
  await seedFeedback(reputation, sketchyId, [10, 15, 12]);

  const good = await reputation.getSummary(goodId, [], "", "");
  const sketchy = await reputation.getSummary(sketchyId, [], "", "");

  console.log("---- DEPLOYED (copy into .env) ----");
  console.log("IDENTITY_REGISTRY_ADDRESS=" + identityAddr);
  console.log("REPUTATION_REGISTRY_ADDRESS=" + reputationAddr);
  console.log("---- agents ----");
  console.log(`GOOD     agentId=${goodId} summary(count,value,dec)=${good}`);
  console.log(`SKETCHY  agentId=${sketchyId} summary(count,value,dec)=${sketchy}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
