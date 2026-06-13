package tech.treasury.agent;

import org.springframework.stereotype.Service;
import tech.treasury.domain.AgentEntity;
import tech.treasury.repo.AgentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/** Authenticates agents by API key (stored only as a SHA-256 hash). */
@Service
public class AgentService {

    private final AgentRepository agents;

    public AgentService(AgentRepository agents) {
        this.agents = agents;
    }

    public Optional<AgentEntity> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return agents.findByApiKeyHash(sha256Hex(apiKey));
    }

    public static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
