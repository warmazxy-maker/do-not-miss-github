package com.donotmiss.backend.agentlog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentBadCaseRepository extends JpaRepository<AgentBadCaseEntity, Long> {
    Optional<AgentBadCaseEntity> findByCaseKey(String caseKey);

    List<AgentBadCaseEntity> findTop30ByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentBadCaseEntity> findTop50ByStatusOrderByCreatedAtAsc(AgentBadCaseStatus status);

    List<AgentBadCaseEntity> findByAgentRunIdOrderByCreatedAtDesc(Long agentRunId);
}
