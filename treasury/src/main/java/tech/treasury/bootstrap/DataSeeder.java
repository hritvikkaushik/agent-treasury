package tech.treasury.bootstrap;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tech.treasury.agent.AgentService;
import tech.treasury.domain.AgentEntity;
import tech.treasury.repo.AgentRepository;
import tech.treasury.reputation.StubReputationProvider;

import java.util.Set;

/**
 * Seeds a demo agent and two merchants for local runs / the demo:
 * a high-reputation merchant (paid) and a low-reputation one (blocked by the reputation gate).
 * Both are on the allowlist, so reputation — not the allowlist — is what differentiates them.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    public static final String DEMO_AGENT_ID = "agent-1";
    public static final String DEMO_API_KEY = "demo-key-agent-1";
    public static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    public static final String GOOD_MERCHANT = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";
    public static final String SKETCHY_MERCHANT = "0x000000000000000000000000000000000000dEaD";

    private final AgentRepository agents;
    private final StubReputationProvider reputation;

    public DataSeeder(AgentRepository agents, StubReputationProvider reputation) {
        this.agents = agents;
        this.reputation = reputation;
    }

    @Override
    public void run(String... args) {
        // Create-if-absent (not upsert) so policy edits made via the admin dashboard survive restarts.
        // The truncate-wipes-agent footgun is handled by scripts/demo-reset.sh (never truncates agents).
        if (!agents.existsById(DEMO_AGENT_ID)) {
            agents.save(new AgentEntity(
                    DEMO_AGENT_ID,
                    "Research Agent",
                    AgentService.sha256Hex(DEMO_API_KEY),
                    500_000,        // 0.50 USDC per-tx cap
                    5_000_000,      // 5.00 USDC daily budget
                    5,              // 5 payments / minute
                    60,             // min counterparty reputation
                    Set.of(GOOD_MERCHANT, SKETCHY_MERCHANT),
                    Set.of(USDC)));
        }
        // Stub reputations are in-memory, so (re)set them every boot regardless.
        reputation.set(GOOD_MERCHANT, 85);
        reputation.set(SKETCHY_MERCHANT, 12);
    }
}
