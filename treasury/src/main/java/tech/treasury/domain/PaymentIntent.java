package tech.treasury.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tech.treasury.policy.DenialReason;

import java.time.Instant;
import java.util.UUID;

/** A single payment request and its lifecycle state. The idempotency key makes (re)creation safe. */
@Entity
@Table(name = "payment_intent")
public class PaymentIntent {

    @Id
    private UUID id;

    @Column(name = "agent_id")
    private String agentId;

    private String payee;

    private String asset;

    @Column(name = "amount_atomic")
    private long amountAtomic;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    private PaymentIntentState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "denial_reason")
    private DenialReason denialReason;

    @Column(name = "tx_hash")
    private String txHash;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    public PaymentIntent(UUID id, String agentId, String payee, String asset, long amountAtomic,
                         String idempotencyKey, Instant now) {
        this.id = id;
        this.agentId = agentId;
        this.payee = payee;
        this.asset = asset;
        this.amountAtomic = amountAtomic;
        this.idempotencyKey = idempotencyKey;
        this.state = PaymentIntentState.REQUESTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Move to a new state, enforcing the transition rules. Use {@link #deny} for denials so the
     * reason is recorded.
     */
    public void transitionTo(PaymentIntentState target, Instant now) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException("Illegal transition " + state + " -> " + target
                    + " for intent " + id);
        }
        this.state = target;
        this.updatedAt = now;
    }

    public void deny(DenialReason reason, Instant now) {
        transitionTo(PaymentIntentState.DENIED, now);
        this.denialReason = reason;
    }

    public void markSettled(String txHash, Instant now) {
        transitionTo(PaymentIntentState.SETTLED, now);
        this.txHash = txHash;
    }

    public UUID getId() {
        return id;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getPayee() {
        return payee;
    }

    public String getAsset() {
        return asset;
    }

    public long getAmountAtomic() {
        return amountAtomic;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentIntentState getState() {
        return state;
    }

    public DenialReason getDenialReason() {
        return denialReason;
    }

    public String getTxHash() {
        return txHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
