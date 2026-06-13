package tech.treasury.policy;

/** Machine-readable reason a payment was blocked. Surfaced to the agent and the dashboard. */
public enum DenialReason {
    ASSET_NOT_ALLOWED("asset is not on the agent's allowlist"),
    MERCHANT_NOT_ALLOWED("counterparty is not on the agent's merchant allowlist"),
    COUNTERPARTY_UNKNOWN("counterparty has no on-chain reputation / is unregistered"),
    REPUTATION_BELOW_THRESHOLD("counterparty reputation is below the agent's minimum"),
    PER_TX_CAP_EXCEEDED("amount exceeds the per-transaction cap"),
    DAILY_BUDGET_EXHAUSTED("payment would exceed the agent's daily budget"),
    VELOCITY_LIMIT_EXCEEDED("too many payments in the rate-limit window");

    private final String detail;

    DenialReason(String detail) {
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
