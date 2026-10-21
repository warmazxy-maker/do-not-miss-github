package com.donotmiss.backend.resumeinterview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeInterviewEvidenceRepository extends JpaRepository<ResumeInterviewEvidenceEntity, Long> {
    List<ResumeInterviewEvidenceEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<ResumeInterviewEvidenceEntity> findBySessionIdAndStatusOrderByCreatedAtAsc(
            Long sessionId,
            ResumeInterviewEvidenceStatus status
    );

    Optional<ResumeInterviewEvidenceEntity> findByQuestionIdAndAnswerId(Long questionId, Long answerId);
}
