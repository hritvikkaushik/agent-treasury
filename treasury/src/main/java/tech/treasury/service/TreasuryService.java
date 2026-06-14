package tech.treasury.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import tech.treasury.reputation.FeedbackWriter;
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

    private static final Logger log = LoggerFactory.getLogger(TreasuryService.class);

    private final AgentRepository agents;
    private final PaymentIntentService intents;
    private final LedgerService ledger;
    private final PolicyEngine policy;
    private final ReputationProvider reputation;
    private final PaymentExecutor executor;
    private final FeedbackWriter feedbackWriter;

    public TreasuryService(AgentRepository agents, PaymentIntentService intents, LedgerService ledger,
                           PolicyEngine policy, ReputationProvider reputation, PaymentExecutor executor,
                           FeedbackWriter feedbackWriter) {
        this.agents = agents;
        this.intents = intents;
        this.ledger = ledger;
        this.policy = policy;
        this.reputation = reputation;
        this.executor = executor;
        this.feedbackWriter = feedbackWriter;
    }

    @Transactional
    public PaymentResult process(String agentId, String payee, String asset, long amountAtomic,
                                 String idempotencyKey, Instant now) {
        // Count prior payments BEFORE creating this intent, so velocity excludes the current attempt.
        long priorPayments = intents.recentPaymentCount(agentId, now);

        log.info("processing payment: agent={} payee={} amount={}", agentId, payee, amountAtomic);

        PaymentIntent intent = intents.getOrCreate(agentId, payee, asset, amountAtomic, idempotencyKey, now);
        if (intent.getState().isTerminal()) {
            log.info("  idempotent replay (key={}) -> {} {}", idempotencyKey, intent.getState(),
                    intent.getDenialReason() != null ? intent.getDenialReason() : "");
            return PaymentResult.of(intent); // never double-spend
        }

        AgentEntity agent = agents.findById(agentId)
                .orElseThrow(() -> new IllegalStateException("Unknown agent: " + agentId));

        long spentToday = ledger.spentTodayAtomic(agentId, now);
        Integer rep = reputation.reputationOf(payee);
        log.info("  policy inputs: spentToday={} recentPayments={} counterpartyReputation={}",
                spentToday, priorPayments, rep == null ? "unknown" : rep);

        PaymentContext ctx = new PaymentContext(
                agentId, payee, asset, amountAtomic, spentToday, (int) priorPayments, rep);

        Decision decision = policy.evaluate(ctx, agent.toPolicy());
        if (!decision.allowed()) {
            intent.deny(decision.reason(), now);
            log.info("  DENIED: agent={} payee={} amount={} reason={}",
                    agentId, payee, amountAtomic, decision.reason());
            return PaymentResult.of(intents.save(intent));
        }

        log.info("  approved -> signing + settling…");
        intent.transitionTo(PaymentIntentState.APPROVED, now);
        intent.transitionTo(PaymentIntentState.SIGNED, now);

        PaymentExecutor.ExecutionResult exec = executor.execute(intent);
        if (exec.success()) {
            ledger.recordPayment(intent.getId(), agentId, amountAtomic, now);
            intent.markSettled(exec.txHash(), now);
            feedbackWriter.recordSuccessfulPayment(payee); // async, best-effort: closes the reputation loop
            log.info("  SETTLED: agent={} payee={} amount={} tx={}", agentId, payee, amountAtomic, exec.txHash());
        } else {
            intent.transitionTo(PaymentIntentState.FAILED, now);
            log.warn("  FAILED: agent={} payee={} amount={} error={}", agentId, payee, amountAtomic, exec.error());
        }
        return PaymentResult.of(intents.save(intent));
    }
}
