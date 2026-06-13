package tech.treasury.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.treasury.domain.AgentEntity;
import tech.treasury.ledger.LedgerService;
import tech.treasury.policy.AgentPolicy;
import tech.treasury.policy.DenialReason;
import tech.treasury.repo.AgentRepository;
import tech.treasury.repo.PaymentIntentRepository;

import java.time.Instant;
import java.util.List;

/** Read-only JSON feed for the dashboard (static/index.html polls these). */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final AgentRepository agents;
    private final PaymentIntentRepository intents;
    private final LedgerService ledger;

    public DashboardController(AgentRepository agents, PaymentIntentRepository intents, LedgerService ledger) {
        this.agents = agents;
        this.intents = intents;
        this.ledger = ledger;
    }

    @GetMapping("/agents")
    public List<AgentView> agents() {
        Instant now = Instant.now();
        return agents.findAll().stream().map(a -> {
            AgentPolicy p = a.toPolicy();
            return new AgentView(a.getId(), a.getName(), p.perTxCapAtomic(), p.dailyBudgetAtomic(),
                    ledger.spentTodayAtomic(a.getId(), now), p.velocityPerMinute(), p.minReputation());
        }).toList();
    }

    @GetMapping("/payments")
    public List<PaymentView> payments() {
        return intents.findTop50ByOrderByCreatedAtDesc().stream().map(i -> {
            DenialReason reason = i.getDenialReason();
            return new PaymentView(
                    i.getId().toString(), i.getAgentId(), i.getPayee(), i.getAmountAtomic(),
                    i.getState().name(),
                    reason == null ? null : reason.name(),
                    reason == null ? null : reason.detail(),
                    i.getTxHash(),
                    i.getCreatedAt().toString());
        }).toList();
    }

    public record AgentView(String id, String name, long perTxCapAtomic, long dailyBudgetAtomic,
                            long spentTodayAtomic, int velocityPerMinute, int minReputation) {
    }

    public record PaymentView(String intentId, String agentId, String payee, long amountAtomic,
                              String state, String denialReason, String denialDetail, String txHash,
                              String createdAt) {
    }
}
