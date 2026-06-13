package tech.treasury.policy;

import java.util.Set;

/**
 * Per-agent spending policy. All monetary fields are in atomic asset units (e.g. USDC has 6
 * decimals, so 10000 = 0.01 USDC). Address sets are compared case-insensitively.
 *
 * @param perTxCapAtomic    max value of a single payment
 * @param dailyBudgetAtomic max total value per UTC day
 * @param velocityPerMinute max number of payments allowed in the trailing 60s window
 * @param allowedMerchants  permitted counterparty addresses (empty = none allowed; deny-by-default)
 * @param allowedAssets     permitted asset (token) addresses
 * @param minReputation     hard floor on counterparty reputation (0-100); below this → deny
 */
public record AgentPolicy(
        long perTxCapAtomic,
        long dailyBudgetAtomic,
        int velocityPerMinute,
        Set<String> allowedMerchants,
        Set<String> allowedAssets,
        int minReputation
) {
    public AgentPolicy {
        allowedMerchants = allowedMerchants == null ? Set.of() : Set.copyOf(allowedMerchants);
        allowedAssets = allowedAssets == null ? Set.of() : Set.copyOf(allowedAssets);
    }
}
