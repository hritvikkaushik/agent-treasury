package tech.treasury.reputation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ERC-8004 on-chain reputation config (Avalanche Fuji). Addresses come from the deploy. */
@ConfigurationProperties("erc8004")
public record Erc8004Properties(
        boolean enabled,
        String identityRegistry,
        String reputationRegistry,
        String rpcUrl,
        long chainId,
        String treasuryPrivateKey
) {
}
