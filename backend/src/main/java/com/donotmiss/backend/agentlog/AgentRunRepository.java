package com.donotmiss.backend.agentlog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {
    List<AgentRunEntity> findTop30ByUserIdOrderByStartedAtDesc(String userId);

    Optional<AgentRunEntity> findByIdAndUserId(Long id, String userId);
}
