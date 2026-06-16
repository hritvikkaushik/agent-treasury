package tech.treasury.agent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tech.treasury.api.CreateAgentRequest;
import tech.treasury.api.UpdateAgentRequest;
import tech.treasury.domain.AgentEntity;
import tech.treasury.repo.AgentRepository;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime agent provisioning: create/update agents and mint API keys (stored only as hashes). */
@Service
public class AgentAdminService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentRepository agents;

    public AgentAdminService(AgentRepository agents) {
        this.agents = agents;
    }

    /** A newly created agent plus its plaintext API key (returned to the caller exactly once). */
    public record Created(AgentEntity agent, String apiKey) {
    }

    @Transactional
    public Created create(CreateAgentRequest req) {
        String id = (req.id() == null || req.id().isBlank()) ? "agent-" + randomHex(3) : req.id().trim();
        if (agents.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "agent id already exists: " + id);
        }
        String apiKey = newApiKey();
        AgentEntity agent = new AgentEntity(
                id, req.name(), AgentService.sha256Hex(apiKey),
                req.perTxCapAtomic(), req.dailyBudgetAtomic(), req.velocityPerMinute(), req.minReputation(),
                toSet(req.allowedMerchants()), toSet(req.allowedAssets()));
        return new Created(agents.save(agent), apiKey);
    }

    @Transactional
    public AgentEntity update(String id, UpdateAgentRequest req) {
        AgentEntity agent = require(id);
        agent.rename(req.name());
        agent.updatePolicy(req.perTxCapAtomic(), req.dailyBudgetAtomic(),
                req.velocityPerMinute(), req.minReputation());
        agent.setAllowedMerchants(toSet(req.allowedMerchants()));
        agent.setAllowedAssets(toSet(req.allowedAssets()));
        return agents.save(agent);
    }

    @Transactional
    public String rotateKey(String id) {
        AgentEntity agent = require(id);
        String apiKey = newApiKey();
        agent.setApiKeyHash(AgentService.sha256Hex(apiKey));
        agents.save(agent);
        return apiKey;
    }

    @Transactional
    public void delete(String id) {
        agents.delete(require(id));
    }

    private AgentEntity require(String id) {
        return agents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no agent: " + id));
    }

    private static Set<String> toSet(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }

    private static String newApiKey() {
        return "atk_" + randomHex(16);
    }

    /** Returns {@code 2*numBytes} lowercase hex characters of randomness. */
    private static String randomHex(int numBytes) {
        byte[] b = new byte[numBytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(numBytes * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
