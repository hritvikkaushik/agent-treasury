package tech.treasury.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import tech.treasury.domain.AgentEntity;
import tech.treasury.reputation.StubReputationProvider;
import tech.treasury.repo.AgentRepository;
import tech.treasury.repo.JournalEntryRepository;
import tech.treasury.repo.PaymentIntentRepository;
import tech.treasury.service.TreasuryService;

import java.time.Instant;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardControllerTest {

    private static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    private static final String GOOD = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";

    @Autowired MockMvc mvc;
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
        agentRepo.save(new AgentEntity("agent-1", "Research Agent", "hash", 500_000, 5_000_000, 5, 60,
                Set.of(GOOD), Set.of(USDC)));
        reputation.set(GOOD, 85);
        treasury.process("agent-1", GOOD, USDC, 100_000, "k1", Instant.now());
    }

    @Test
    void agentsEndpointReportsBudgetAndSpend() throws Exception {
        mvc.perform(get("/api/dashboard/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("agent-1"))
                .andExpect(jsonPath("$[0].dailyBudgetAtomic").value(5_000_000))
                .andExpect(jsonPath("$[0].spentTodayAtomic").value(100_000));
    }

    @Test
    void paymentsEndpointListsTheSettledPayment() throws Exception {
        mvc.perform(get("/api/dashboard/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("SETTLED"))
                .andExpect(jsonPath("$[0].payee").value(GOOD))
                .andExpect(jsonPath("$[0].amountAtomic").value(100_000));
    }
}
