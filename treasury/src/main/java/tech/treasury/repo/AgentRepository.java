package tech.treasury.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.treasury.domain.AgentEntity;

import java.util.Optional;

public interface AgentRepository extends JpaRepository<AgentEntity, String> {

    Optional<AgentEntity> findByApiKeyHash(String apiKeyHash);
}
