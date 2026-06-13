package tech.treasury;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tech.treasury.domain.PaymentIntent;
import tech.treasury.domain.PaymentIntentState;
import tech.treasury.domain.AgentEntity;
import tech.treasury.intent.PaymentIntentService;
import tech.treasury.ledger.LedgerService;
import tech.treasury.repo.AgentRepository;
import tech.treasury.repo.JournalEntryRepository;
import tech.treasury.repo.PaymentIntentRepository;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises persistence, the ledger, and idempotency against a real Postgres (localhost:5432).
 * Start it with: ./scripts/dev-db.sh up
 */
@SpringBootTest
class PersistenceIntegrationTest {

    private static final String AGENT = "agent-1";
    private static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    private static final String MERCHANT = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";

    @Autowired PaymentIntentService intents;
    @Autowired LedgerService ledger;
    @Autowired PaymentIntentRepository intentRepo;
    @Autowired JournalEntryRepository journalRepo;
    @Autowired AgentRepository agentRepo;

    @BeforeEach
    void resetAndSeed() {
        journalRepo.deleteAll();
        intentRepo.deleteAll();
        agentRepo.deleteAll();
        agentRepo.save(new AgentEntity(AGENT, "Research Agent", "hash", 500_000, 5_000_000, 5, 60,
                Set.of(MERCHANT), Set.of(USDC)));
    }

    @Test
    void createIsIdempotentOnKey() {
        Instant now = Instant.now();
        PaymentIntent first = intents.getOrCreate(AGENT, MERCHANT, USDC, 100_000, "key-1", now);
        PaymentIntent second = intents.getOrCreate(AGENT, MERCHANT, USDC, 100_000, "key-1", now);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(intentRepo.count()).isEqualTo(1);
        assertThat(first.getState()).isEqualTo(PaymentIntentState.REQUESTED);
    }

    @Test
    void ledgerRecordsBalancedEntriesAndTracksDailySpend() {
        Instant now = Instant.now();
        PaymentIntent intent = intents.getOrCreate(AGENT, MERCHANT, USDC, 100_000, "key-2", now);

        ledger.recordPayment(intent.getId(), AGENT, 100_000, now);
        ledger.recordPayment(intent.getId(), AGENT, 250_000, now);

        // two postings per payment, balanced (debits == credits)
        long debits = journalRepo.findAll().stream().mapToLong(j -> j.getDebitAtomic()).sum();
        long credits = journalRepo.findAll().stream().mapToLong(j -> j.getCreditAtomic()).sum();
        assertThat(debits).isEqualTo(credits).isEqualTo(350_000);

        assertThat(ledger.spentTodayAtomic(AGENT, now)).isEqualTo(350_000);
    }

    @Test
    void settlementTransitionRecordsTxHash() {
        Instant now = Instant.now();
        PaymentIntent intent = intents.getOrCreate(AGENT, MERCHANT, USDC, 100_000, "key-3", now);
        intent.transitionTo(PaymentIntentState.APPROVED, now);
        intent.transitionTo(PaymentIntentState.SIGNED, now);
        intent.markSettled("0xabc123", now);
        intents.save(intent);

        PaymentIntent reloaded = intentRepo.findById(intent.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(PaymentIntentState.SETTLED);
        assertThat(reloaded.getTxHash()).isEqualTo("0xabc123");
    }

    @Test
    void recentPaymentCountReflectsVelocity() {
        Instant now = Instant.now();
        intents.getOrCreate(AGENT, MERCHANT, USDC, 100_000, "v-1", now);
        intents.getOrCreate(AGENT, MERCHANT, USDC, 100_000, "v-2", now);
        intents.getOrCreate(AGENT, MERCHANT, USDC, 100_000, "v-3", now);

        assertThat(intents.recentPaymentCount(AGENT, now.plusSeconds(1))).isEqualTo(3);
        // outside the 60s window
        assertThat(intents.recentPaymentCount(AGENT, now.plusSeconds(120))).isEqualTo(0);
    }
}
