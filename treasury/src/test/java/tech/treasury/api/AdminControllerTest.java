package tech.treasury.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tech.treasury.reputation.StubReputationProvider;
import tech.treasury.repo.AgentRepository;
import tech.treasury.repo.JournalEntryRepository;
import tech.treasury.repo.PaymentIntentRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

    private static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    private static final String GOOD = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
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

    private String createBody(String name, long cap, int minRep) {
        return "{\"name\":\"" + name + "\",\"perTxCapAtomic\":" + cap + ",\"dailyBudgetAtomic\":5000000,"
                + "\"velocityPerMinute\":5,\"minReputation\":" + minRep + ",\"allowedMerchants\":[\"" + GOOD
                + "\"],\"allowedAssets\":[\"" + USDC + "\"]}";
    }

    @Test
    void createsAgentAndTheReturnedKeyAuthenticates() throws Exception {
        String resp = mvc.perform(post("/api/admin/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("Bot A", 500_000, 60)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").exists())
                .andExpect(jsonPath("$.agent.id").exists())
                .andReturn().getResponse().getContentAsString();

        String apiKey = json.readTree(resp).get("apiKey").asText();
        org.assertj.core.api.Assertions.assertThat(apiKey).startsWith("atk_");

        // the freshly minted key works on the payment path
        reputation.set(GOOD, 85);
        mvc.perform(post("/proxy")
                        .header("X-Agent-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payee\":\"" + GOOD + "\",\"asset\":\"" + USDC + "\",\"amountAtomic\":100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SETTLED"));
    }

    @Test
    void updatesPolicy() throws Exception {
        String resp = mvc.perform(post("/api/admin/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("Bot B", 500_000, 60)))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(resp).get("agent").get("id").asText();

        mvc.perform(put("/api/admin/agents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bot B2\",\"perTxCapAtomic\":250000,\"dailyBudgetAtomic\":1000000,"
                                + "\"velocityPerMinute\":2,\"minReputation\":80,\"allowedMerchants\":[\"" + GOOD
                                + "\"],\"allowedAssets\":[\"" + USDC + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bot B2"))
                .andExpect(jsonPath("$.perTxCapAtomic").value(250000))
                .andExpect(jsonPath("$.minReputation").value(80));
    }

    @Test
    void rejectsBlankName() throws Exception {
        mvc.perform(post("/api/admin/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("", 500_000, 60)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesAgent() throws Exception {
        String resp = mvc.perform(post("/api/admin/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("Bot C", 500_000, 60)))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(resp).get("agent").get("id").asText();

        mvc.perform(delete("/api/admin/agents/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/agents")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").isEmpty());
    }
}
