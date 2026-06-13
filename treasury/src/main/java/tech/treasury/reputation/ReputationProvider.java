package tech.treasury.reputation;

/**
 * Supplies a counterparty's reputation (0-100), or null if unknown / unregistered. Phase 1 uses an
 * in-memory stub; Phase 2 swaps in an ERC-8004 on-chain reader without changing callers.
 */
public interface ReputationProvider {

    Integer reputationOf(String counterparty);
}
