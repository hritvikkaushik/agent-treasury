package tech.treasury.intent;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.treasury.domain.PaymentIntent;
import tech.treasury.repo.PaymentIntentRepository;

import java.time.Instant;
import java.util.UUID;

/** Manages the payment-intent lifecycle. Creation is idempotent on the idempotency key. */
@Service
public class PaymentIntentService {

    /** Trailing window used for the velocity rule. */
    private static final long VELOCITY_WINDOW_SECONDS = 60;

    private final PaymentIntentRepository repo;

    public PaymentIntentService(PaymentIntentRepository repo) {
        this.repo = repo;
    }

    /**
     * Return the existing intent for this idempotency key, or create a new one. Safe under concurrent
     * retries: a racing insert hits the unique constraint and we re-read the winner.
     */
    @Transactional
    public PaymentIntent getOrCreate(String agentId, String payee, String asset, long amountAtomic,
                                     String idempotencyKey, Instant now) {
        return repo.findByIdempotencyKey(idempotencyKey).orElseGet(() -> {
            try {
                return repo.saveAndFlush(new PaymentIntent(
                        UUID.randomUUID(), agentId, payee, asset, amountAtomic, idempotencyKey, now));
            } catch (DataIntegrityViolationException race) {
                return repo.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
            }
        });
    }

    /** Count of this agent's intents created within the velocity window ending at {@code now}. */
    public long recentPaymentCount(String agentId, Instant now) {
        return repo.countByAgentIdAndCreatedAtAfter(agentId, now.minusSeconds(VELOCITY_WINDOW_SECONDS));
    }

    @Transactional
    public PaymentIntent save(PaymentIntent intent) {
        return repo.save(intent);
    }
}
