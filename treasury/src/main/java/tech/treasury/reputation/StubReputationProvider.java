package tech.treasury.reputation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reputation, for Phase 1 and tests. Addresses are matched case-insensitively. Phase 2's
 * chain-backed provider can replace this (e.g. via @Primary / a profile) without touching callers.
 */
@Component
public class StubReputationProvider implements ReputationProvider {

    private final Map<String, Integer> scores = new ConcurrentHashMap<>();

    public void set(String counterparty, int score) {
        scores.put(counterparty.toLowerCase(), score);
    }

    public void clear() {
        scores.clear();
    }

    @Override
    public Integer reputationOf(String counterparty) {
        return counterparty == null ? null : scores.get(counterparty.toLowerCase());
    }
}
