package tech.treasury.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.treasury.domain.AgentEntity;

public interface AgentRepository extends JpaRepository<AgentEntity, String> {
}
