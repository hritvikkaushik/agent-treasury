package tech.treasury.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.treasury.api.PaymentResult;
import tech.treasury.domain.AgentEntity;
import tech.treasury.domain.PaymentIntent;
import tech.treasury.domain.PaymentIntentState;
import tech.treasury.intent.PaymentIntentService;
import tech.treasury.ledger.LedgerService;
import tech.treasury.payment.PaymentExecutor;
import tech.treasury.policy.Decision;
import tech.treasury.policy.PaymentContext;
import tech.treasury.policy.PolicyEngine;
import tech.treasury.reputation.ReputationProvider;
import tech.treasury.repo.AgentRepository;

import java.time.Instant;

/**
 * Orchestrates one payment: idempotent intent creation -> policy evaluation (budget, velocity,
 * counterparty reputation) -> sign/settle -> ledger. Agents never hold keys; the treasury decides
 * and signs only on approval.
 *
 * <p>Phase 2 note: {@link PaymentExecutor#execute} will become a network call; it should then run
 * outside the DB transaction. For the Phase-1 stub (instant) keeping it inline is fine.
 */
@Service
public class TreasuryService {

    private final AgentRepository agents;
    private final PaymentIntentService intents;
    private final LedgerService ledger;
    private final PolicyEngine policy;
    private final ReputationProvider reputation;
    private final PaymentExecutor executor;

    public TreasuryService(AgentRepository agents, PaymentIntentService intents, LedgerService ledger,
                           PolicyEngine policy, ReputationProvider reputation, PaymentExecutor executor) {
        this.agents = agents;
        this.intents = intents;
        this.ledger = ledger;
        this.policy = policy;
        this.reputation = reputation;
        this.executor = executor;
    }

    @Transactional
    public PaymentResult process(String agentId, String payee, String asset, long amountAtomic,
                                 String idempotencyKey, Instant now) {
        // Count prior payments BEFORE creating this intent, so velocity excludes the current attempt.
        long priorPayments = intents.recentPaymentCount(agentId, now);

        PaymentIntent intent = intents.getOrCreate(agentId, payee, asset, amountAtomic, idempotencyKey, now);
        if (intent.getState().isTerminal()) {
            return PaymentResult.of(intent); // idempotent replay — never double-spend
        }

        AgentEntity agent = agents.findById(agentId)
                .orElseThrow(() -> new IllegalStateException("Unknown agent: " + agentId));

        PaymentContext ctx = new PaymentContext(
                agentId, payee, asset, amountAtomic,
                ledger.spentTodayAtomic(agentId, now),
                (int) priorPayments,
                reputation.reputationOf(payee));

        Decision decision = policy.evaluate(ctx, agent.toPolicy());
        if (!decision.allowed()) {
            intent.deny(decision.reason(), now);
            return PaymentResult.of(intents.save(intent));
        }

        intent.transitionTo(PaymentIntentState.APPROVED, now);
        intent.transitionTo(PaymentIntentState.SIGNED, now);

        PaymentExecutor.ExecutionResult exec = executor.execute(intent);
        if (exec.success()) {
            ledger.recordPayment(intent.getId(), agentId, amountAtomic, now);
            intent.markSettled(exec.txHash(), now);
        } else {
            intent.transitionTo(PaymentIntentState.FAILED, now);
        }
        return PaymentResult.of(intents.save(intent));
    }
}
