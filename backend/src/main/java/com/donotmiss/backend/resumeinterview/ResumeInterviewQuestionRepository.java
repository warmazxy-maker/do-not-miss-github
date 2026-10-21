package com.donotmiss.backend.resumeinterview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeInterviewQuestionRepository extends JpaRepository<ResumeInterviewQuestionEntity, Long> {
    List<ResumeInterviewQuestionEntity> findBySessionIdOrderBySequenceNoAsc(Long sessionId);

    Optional<ResumeInterviewQuestionEntity> findByIdAndSessionId(Long id, Long sessionId);
}
