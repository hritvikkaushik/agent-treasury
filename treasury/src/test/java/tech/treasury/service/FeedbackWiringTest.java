package tech.treasury.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import tech.treasury.domain.AgentEntity;
import tech.treasury.domain.PaymentIntentState;
import tech.treasury.reputation.FeedbackWriter;
import tech.treasury.reputation.StubReputationProvider;
import tech.treasury.repo.AgentRepository;
import tech.treasury.repo.JournalEntryRepository;
import tech.treasury.repo.PaymentIntentRepository;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Feedback is written on settlement, never on denial. */
@SpringBootTest
class FeedbackWiringTest {

    private static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    private static final String GOOD = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";

    @MockBean FeedbackWriter feedbackWriter;
    @Autowired TreasuryService treasury;
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
        agentRepo.save(new AgentEntity("a", "Agent", "hash", 500_000, 5_000_000, 5, 60,
                Set.of(GOOD), Set.of(USDC)));
    }

    @Test
    void settlementTriggersFeedback() {
        reputation.set(GOOD, 85);
        var r = treasury.process("a", GOOD, USDC, 100_000, "k1", Instant.now());

        assertThat(r.state()).isEqualTo(PaymentIntentState.SETTLED);
        verify(feedbackWriter, times(1)).recordSuccessfulPayment(GOOD);
    }

    @Test
    void denialWritesNoFeedback() {
        reputation.set(GOOD, 50); // below floor 60
        var r = treasury.process("a", GOOD, USDC, 100_000, "k1", Instant.now());

        assertThat(r.state()).isEqualTo(PaymentIntentState.DENIED);
        verify(feedbackWriter, never()).recordSuccessfulPayment(GOOD);
    }
}
