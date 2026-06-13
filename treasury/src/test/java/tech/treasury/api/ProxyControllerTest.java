package tech.treasury.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tech.treasury.agent.AgentService;
import tech.treasury.domain.AgentEntity;
import tech.treasury.reputation.StubReputationProvider;
import tech.treasury.repo.AgentRepository;
import tech.treasury.repo.JournalEntryRepository;
import tech.treasury.repo.PaymentIntentRepository;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProxyControllerTest {

    private static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    private static final String GOOD = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";
    private static final String SKETCHY = "0x000000000000000000000000000000000000dEaD";
    private static final String API_KEY = "demo-key-agent-1";

    @Autowired MockMvc mvc;
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
        agentRepo.save(new AgentEntity("agent-1", "Research Agent",
                AgentService.sha256Hex(API_KEY), 500_000, 5_000_000, 5, 60,
                Set.of(GOOD, SKETCHY), Set.of(USDC)));
        reputation.set(GOOD, 85);
        reputation.set(SKETCHY, 12);
    }

    @Test
    void rejectsMissingOrInvalidAgentKey() throws Exception {
        mvc.perform(post("/proxy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD, 100_000)))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/proxy")
                        .header("X-Agent-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD, 100_000)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void settlesAGoodPayment() throws Exception {
        mvc.perform(post("/proxy")
                        .header("X-Agent-Key", API_KEY)
                        .header("Idempotency-Key", "good-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD, 100_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SETTLED"))
                .andExpect(jsonPath("$.txHash").isNotEmpty());
    }

    @Test
    void blocksLowReputationCounterpartyWith402() throws Exception {
        mvc.perform(post("/proxy")
                        .header("X-Agent-Key", API_KEY)
                        .header("Idempotency-Key", "sketchy-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKETCHY, 100_000)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.state").value("DENIED"))
                .andExpect(jsonPath("$.denialReason").value("REPUTATION_BELOW_THRESHOLD"));
    }

    private static String body(String payee, long amount) {
        return "{\"payee\":\"" + payee + "\",\"asset\":\"" + USDC + "\",\"amountAtomic\":" + amount + "}";
    }
}
