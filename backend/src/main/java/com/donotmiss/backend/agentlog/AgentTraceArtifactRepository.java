package com.donotmiss.backend.agentlog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTraceArtifactRepository extends JpaRepository<AgentTraceArtifactEntity, Long> {
    List<AgentTraceArtifactEntity> findByRunIdOrderByIdAsc(Long runId);
}
