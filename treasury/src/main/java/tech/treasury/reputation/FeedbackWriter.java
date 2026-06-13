package tech.treasury.reputation;

/**
 * Writes reputation feedback after a payment. Best-effort and asynchronous — never on the request
 * path, never fails a payment. Phase 1 uses a no-op; the ERC-8004 impl writes {@code giveFeedback}.
 */
public interface FeedbackWriter {

    /** Record a successful payment to {@code payee} as positive reputation feedback. */
    void recordSuccessfulPayment(String payee);
}
