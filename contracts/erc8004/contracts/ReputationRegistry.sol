// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface IIdentityRegistry {
    function getAgentWallet(uint256 agentId) external view returns (address);
}

/**
 * Minimal ERC-8004-compatible Reputation Registry.
 *
 * Matches the reference signatures the treasury uses: `giveFeedback(...)` and
 * `getSummary(...) -> (count, summaryValue, summaryValueDecimals)`. `summaryValue` is the average of
 * non-revoked feedback values. By our convention feedback is given as a 0-100 score with
 * valueDecimals = 0, so the summary reads directly as a 0-100 reputation.
 */
contract ReputationRegistry {

    struct Feedback {
        address client;
        int128 value;
        uint8 valueDecimals;
        bool revoked;
    }

    IIdentityRegistry public immutable identity;
    mapping(uint256 => Feedback[]) private feedbackByAgent;

    event FeedbackGiven(uint256 indexed agentId, address indexed client, int128 value, uint8 valueDecimals);

    constructor(address identityRegistry) {
        identity = IIdentityRegistry(identityRegistry);
    }

    function giveFeedback(
        uint256 agentId,
        int128 value,
        uint8 valueDecimals,
        string calldata tag1,
        string calldata tag2,
        string calldata endpoint,
        string calldata feedbackURI,
        bytes32 feedbackHash
    ) external {
        require(identity.getAgentWallet(agentId) != address(0), "unknown agent");
        require(valueDecimals <= 18, "decimals too large");
        feedbackByAgent[agentId].push(Feedback(msg.sender, value, valueDecimals, false));
        emit FeedbackGiven(agentId, msg.sender, value, valueDecimals);
    }

    /**
     * Aggregate (non-revoked) feedback for an agent. clientAddresses/tag filters are accepted for
     * signature compatibility but not applied in this minimal impl. Returns the average as
     * summaryValue with summaryValueDecimals (mode of inputs; 0 under our convention).
     */
    function getSummary(
        uint256 agentId,
        address[] calldata clientAddresses,
        string calldata tag1,
        string calldata tag2
    ) external view returns (uint64 count, int128 summaryValue, uint8 summaryValueDecimals) {
        Feedback[] storage list = feedbackByAgent[agentId];
        int256 sum = 0;
        uint64 n = 0;
        for (uint256 i = 0; i < list.length; i++) {
            if (!list[i].revoked) {
                sum += int256(list[i].value);
                n++;
            }
        }
        if (n == 0) {
            return (0, 0, 0);
        }
        return (n, int128(sum / int256(uint256(n))), 0);
    }
}
