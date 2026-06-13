package tech.treasury.payment.x402;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * x402 / Avalanche Fuji settlement config. Values come from application.yml (env-backed).
 * Verified constants live in SPIKE-FINDINGS.md.
 */
@ConfigurationProperties("x402")
public record X402Properties(
        boolean enabled,
        String facilitatorUrl,
        String network,
        long chainId,
        String asset,
        String usdcDomainName,
        String usdcDomainVersion,
        long maxTimeoutSeconds,
        String treasuryPrivateKey
) {
}
