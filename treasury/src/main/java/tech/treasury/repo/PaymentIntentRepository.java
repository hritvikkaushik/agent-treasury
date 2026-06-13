package tech.treasury.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.treasury.domain.PaymentIntent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

    long countByAgentIdAndCreatedAtAfter(String agentId, Instant after);

    List<PaymentIntent> findTop50ByOrderByCreatedAtDesc();
}
