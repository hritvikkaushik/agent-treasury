package tech.treasury.ledger;

/** Ledger account naming. Each agent has a budget account; merchants share a payable clearing account. */
public final class Accounts {

    public static final String MERCHANT_PAYABLE = "merchant:payable";

    private Accounts() {
    }

    /** Asset account debited when an agent spends. Daily spend = sum of debits here for the UTC day. */
    public static String budget(String agentId) {
        return "agent:" + agentId + ":budget";
    }
}
