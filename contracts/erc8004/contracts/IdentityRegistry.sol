// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/**
 * Minimal ERC-8004-compatible Identity Registry.
 *
 * Implements the subset the Agent Treasury needs: register an agent (wallet -> agentId) and resolve
 * an agent's wallet. `register()` is the ERC-8004-shaped entry (mints to msg.sender); `registerFor`
 * is a demo convenience to register arbitrary merchant wallets during seeding.
 */
contract IdentityRegistry {

    uint256 public nextAgentId = 1;
    mapping(uint256 => address) public agentWallet;   // agentId -> wallet
    mapping(address => uint256) public agentIdOf;      // wallet -> agentId (0 = unregistered)
    mapping(uint256 => string) public agentURI;        // agentId -> registration file URI

    event Registered(uint256 indexed agentId, address indexed wallet, string agentURI);

    function register(string calldata uri) external returns (uint256) {
        return _register(msg.sender, uri);
    }

    function registerFor(address wallet, string calldata uri) external returns (uint256) {
        return _register(wallet, uri);
    }

    function getAgentWallet(uint256 agentId) external view returns (address) {
        return agentWallet[agentId];
    }

    function _register(address wallet, string calldata uri) internal returns (uint256) {
        require(wallet != address(0), "zero wallet");
        require(agentIdOf[wallet] == 0, "already registered");
        uint256 id = nextAgentId++;
        agentWallet[id] = wallet;
        agentIdOf[wallet] = id;
        agentURI[id] = uri;
        emit Registered(id, wallet, uri);
        return id;
    }
}
