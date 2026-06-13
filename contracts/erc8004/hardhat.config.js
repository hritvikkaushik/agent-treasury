require("@nomicfoundation/hardhat-ethers");

// Reads RPC_URL and TREASURY_PRIVATE_KEY from the environment (passed via docker --env-file .env).
module.exports = {
  solidity: {
    version: "0.8.24",
    settings: { optimizer: { enabled: true, runs: 200 } },
  },
  networks: {
    fuji: {
      url: process.env.RPC_URL || "https://api.avax-test.network/ext/bc/C/rpc",
      accounts: process.env.TREASURY_PRIVATE_KEY ? [process.env.TREASURY_PRIVATE_KEY] : [],
      chainId: 43113,
    },
  },
};
