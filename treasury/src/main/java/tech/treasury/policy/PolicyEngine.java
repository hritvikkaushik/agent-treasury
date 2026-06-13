package tech.treasury.policy;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Pure, deterministic policy evaluation — no I/O, no state. The orchestration layer assembles a
 * {@link PaymentContext} (from request + ledger + reputation oracle) and calls {@link #evaluate}.
 *
 * <p>Deny-by-default: a payment is allowed only if it clears every rule. Reputation acts both as a
 * hard floor ({@code minReputation}) and as a tier that scales the agent's limits — a lower-trust
 * (but still-permitted) counterparty gets a fraction of the normal cap/budget.
 */
@Component
public class PolicyEngine {

    /** Reputation at/above this gets full limits; below it (but above the floor) gets a fraction. */
    static final int FULL_TRUST_THRESHOLD = 80;
    /** Limit multiplier (percent) applied to counterparties between the floor and full trust. */
    static final long REDUCED_LIMIT_PERCENT = 25;

    public Decision evaluate(PaymentContext ctx, AgentPolicy policy) {
        if (!containsIgnoreCase(policy.allowedAssets(), ctx.asset())) {
            return Decision.deny(DenialReason.ASSET_NOT_ALLOWED);
        }
        if (!containsIgnoreCase(policy.allowedMerchants(), ctx.payee())) {
            return Decision.deny(DenialReason.MERCHANT_NOT_ALLOWED);
        }

        Integer reputation = ctx.counterpartyReputation();
        if (reputation == null) {
            return Decision.deny(DenialReason.COUNTERPARTY_UNKNOWN);
        }
        if (reputation < policy.minReputation()) {
            return Decision.deny(DenialReason.REPUTATION_BELOW_THRESHOLD);
        }

        long limitPercent = reputation >= FULL_TRUST_THRESHOLD ? 100 : REDUCED_LIMIT_PERCENT;
        long effectivePerTxCap = policy.perTxCapAtomic() * limitPercent / 100;
        long effectiveDailyBudget = policy.dailyBudgetAtomic() * limitPercent / 100;

        if (ctx.amountAtomic() > effectivePerTxCap) {
            return Decision.deny(DenialReason.PER_TX_CAP_EXCEEDED);
        }
        if (ctx.spentTodayAtomic() + ctx.amountAtomic() > effectiveDailyBudget) {
            return Decision.deny(DenialReason.DAILY_BUDGET_EXHAUSTED);
        }
        if (ctx.recentPaymentCount() >= policy.velocityPerMinute()) {
            return Decision.deny(DenialReason.VELOCITY_LIMIT_EXCEEDED);
        }

        return Decision.allow();
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (value == null) {
            return false;
        }
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
