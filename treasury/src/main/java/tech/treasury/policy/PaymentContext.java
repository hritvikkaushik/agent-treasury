package tech.treasury.policy;

/**
 * Everything the policy engine needs to judge one payment. Assembled by the orchestration layer
 * from the request, the ledger (spent-so-far / velocity), and the reputation oracle.
 *
 * @param agentId                the spending agent
 * @param payee                  counterparty address
 * @param asset                  token (asset) address
 * @param amountAtomic           payment amount in atomic units
 * @param spentTodayAtomic       sum already spent by this agent in the current UTC day
 * @param recentPaymentCount     this agent's payments in the trailing velocity window
 * @param counterpartyReputation 0-100, or null if unknown / unregistered
 */
public record PaymentContext(
        String agentId,
        String payee,
        String asset,
        long amountAtomic,
        long spentTodayAtomic,
        int recentPaymentCount,
        Integer counterpartyReputation
) {
}
