package com.donotmiss.backend.agentlog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunStepRepository extends JpaRepository<AgentRunStepEntity, Long> {
    List<AgentRunStepEntity> findByRunIdOrderBySequenceNoAsc(Long runId);

    long countByRunId(Long runId);
}
