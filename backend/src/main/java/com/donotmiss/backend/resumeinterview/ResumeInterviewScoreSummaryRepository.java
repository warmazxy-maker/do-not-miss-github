package com.donotmiss.backend.resumeinterview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeInterviewScoreSummaryRepository extends JpaRepository<ResumeInterviewScoreSummaryEntity, Long> {
    Optional<ResumeInterviewScoreSummaryEntity> findBySessionId(Long sessionId);
}
