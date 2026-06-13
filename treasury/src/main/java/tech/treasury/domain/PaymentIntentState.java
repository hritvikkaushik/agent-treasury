package tech.treasury.domain;

/**
 * Lifecycle of a payment intent. Transitions are enforced via {@link #canTransitionTo}.
 *
 * <pre>
 *   REQUESTED ─▶ APPROVED ─▶ SIGNED ─▶ SETTLED
 *       │            │           │
 *       └─▶ DENIED   └▶ DENIED   └▶ FAILED ─▶ (reconciliation) ─▶ SETTLED
 * </pre>
 */
public enum PaymentIntentState {
    REQUESTED,
    APPROVED,
    SIGNED,
    SETTLED,
    DENIED,
    FAILED;

    public boolean isTerminal() {
        return this == SETTLED || this == DENIED;
    }

    public boolean canTransitionTo(PaymentIntentState target) {
        return switch (this) {
            case REQUESTED -> target == APPROVED || target == DENIED;
            case APPROVED -> target == SIGNED || target == DENIED;
            case SIGNED -> target == SETTLED || target == FAILED;
            // reconciliation may confirm a previously-failed settlement
            case FAILED -> target == SETTLED;
            case SETTLED, DENIED -> false;
        };
    }
}
