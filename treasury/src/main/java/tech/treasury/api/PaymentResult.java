package tech.treasury.api;

import tech.treasury.domain.PaymentIntent;
import tech.treasury.domain.PaymentIntentState;
import tech.treasury.policy.DenialReason;

import java.util.UUID;

/** Outcome of a payment, returned to the agent and shown on the dashboard. */
public record PaymentResult(
        UUID intentId,
        PaymentIntentState state,
        DenialReason denialReason,
        String denialDetail,
        String txHash
) {
    public static PaymentResult of(PaymentIntent intent) {
        DenialReason reason = intent.getDenialReason();
        return new PaymentResult(
                intent.getId(),
                intent.getState(),
                reason,
                reason == null ? null : reason.detail(),
                intent.getTxHash());
    }
}
