package tech.treasury.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import tech.treasury.policy.AgentPolicy;

import java.util.HashSet;
import java.util.Set;

/** A registered agent and its spending policy. */
@Entity
@Table(name = "agents")
public class AgentEntity {

    @Id
    private String id;

    private String name;

    @Column(name = "api_key_hash")
    private String apiKeyHash;

    @Column(name = "per_tx_cap_atomic")
    private long perTxCapAtomic;

    @Column(name = "daily_budget_atomic")
    private long dailyBudgetAtomic;

    @Column(name = "velocity_per_minute")
    private int velocityPerMinute;

    @Column(name = "min_reputation")
    private int minReputation;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_merchants", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "merchant")
    private Set<String> allowedMerchants = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_assets", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "asset")
    private Set<String> allowedAssets = new HashSet<>();

    protected AgentEntity() {
    }

    public AgentEntity(String id, String name, String apiKeyHash, long perTxCapAtomic,
                       long dailyBudgetAtomic, int velocityPerMinute, int minReputation,
                       Set<String> allowedMerchants, Set<String> allowedAssets) {
        this.id = id;
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.perTxCapAtomic = perTxCapAtomic;
        this.dailyBudgetAtomic = dailyBudgetAtomic;
        this.velocityPerMinute = velocityPerMinute;
        this.minReputation = minReputation;
        this.allowedMerchants = new HashSet<>(allowedMerchants);
        this.allowedAssets = new HashSet<>(allowedAssets);
    }

    /** Project the persisted fields into the immutable value object the policy engine consumes. */
    public AgentPolicy toPolicy() {
        return new AgentPolicy(perTxCapAtomic, dailyBudgetAtomic, velocityPerMinute,
                allowedMerchants, allowedAssets, minReputation);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }
}
