package tech.treasury.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tech.treasury.api.PaymentResult;
import tech.treasury.domain.AgentEntity;
import tech.treasury.domain.PaymentIntentState;
import tech.treasury.ledger.LedgerService;
import tech.treasury.policy.DenialReason;
import tech.treasury.reputation.StubReputationProvider;
import tech.treasury.repo.AgentRepository;
import tech.treasury.repo.JournalEntryRepository;
import tech.treasury.repo.PaymentIntentRepository;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TreasuryServiceTest {

    private static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    private static final String GOOD = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";

    @Autowired TreasuryService treasury;
    @Autowired LedgerService ledger;
    @Autowired StubReputationProvider reputation;
    @Autowired AgentRepository agentRepo;
    @Autowired PaymentIntentRepository intentRepo;
    @Autowired JournalEntryRepository journalRepo;

    @BeforeEach
    void reset() {
        journalRepo.deleteAll();
        intentRepo.deleteAll();
        agentRepo.deleteAll();
        reputation.clear();
    }

    private void seedAgent(String id, long perTxCap, long dailyBudget, int minRep) {
        agentRepo.save(new AgentEntity(id, "Agent " + id, "hash-" + id,
                perTxCap, dailyBudget, 5, minRep, Set.of(GOOD), Set.of(USDC)));
    }

    @Test
    void compliantPaymentSettlesAndPostsToLedger() {
        seedAgent("a", 500_000, 5_000_000, 60);
        reputation.set(GOOD, 85);
        Instant now = Instant.now();

        PaymentResult r = treasury.process("a", GOOD, USDC, 100_000, "k1", now);

        assertThat(r.state()).isEqualTo(PaymentIntentState.SETTLED);
        assertThat(r.txHash()).isNotBlank();
        assertThat(ledger.spentTodayAtomic("a", now)).isEqualTo(100_000);
    }

    @Test
    void lowReputationCounterpartyIsBlockedAndNothingMoves() {
        seedAgent("a", 500_000, 5_000_000, 60);
        reputation.set(GOOD, 50); // below the agent's floor of 60
        Instant now = Instant.now();

        PaymentResult r = treasury.process("a", GOOD, USDC, 100_000, "k1", now);

        assertThat(r.state()).isEqualTo(PaymentIntentState.DENIED);
        assertThat(r.denialReason()).isEqualTo(DenialReason.REPUTATION_BELOW_THRESHOLD);
        assertThat(ledger.spentTodayAtomic("a", now)).isZero();
    }

    @Test
    void dailyBudgetIsEnforcedAcrossPayments() {
        seedAgent("a", 500_000, 150_000, 60); // 0.15 daily budget
        reputation.set(GOOD, 85);
        Instant now = Instant.now();

        PaymentResult first = treasury.process("a", GOOD, USDC, 100_000, "k1", now);
        PaymentResult second = treasury.process("a", GOOD, USDC, 100_000, "k2", now);

        assertThat(first.state()).isEqualTo(PaymentIntentState.SETTLED);
        assertThat(second.state()).isEqualTo(PaymentIntentState.DENIED);
        assertThat(second.denialReason()).isEqualTo(DenialReason.DAILY_BUDGET_EXHAUSTED);
        assertThat(ledger.spentTodayAtomic("a", now)).isEqualTo(100_000); // only the first
    }

    @Test
    void replayWithSameIdempotencyKeyDoesNotDoubleSpend() {
        seedAgent("a", 500_000, 5_000_000, 60);
        reputation.set(GOOD, 85);
        Instant now = Instant.now();

        PaymentResult first = treasury.process("a", GOOD, USDC, 100_000, "dup", now);
        PaymentResult replay = treasury.process("a", GOOD, USDC, 100_000, "dup", now);

        assertThat(replay.intentId()).isEqualTo(first.intentId());
        assertThat(replay.txHash()).isEqualTo(first.txHash());
        assertThat(intentRepo.count()).isEqualTo(1);
        assertThat(ledger.spentTodayAtomic("a", now)).isEqualTo(100_000); // charged once
    }
}
