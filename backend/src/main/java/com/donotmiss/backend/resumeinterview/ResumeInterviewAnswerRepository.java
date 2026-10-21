package com.donotmiss.backend.resumeinterview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeInterviewAnswerRepository extends JpaRepository<ResumeInterviewAnswerEntity, Long> {
    Optional<ResumeInterviewAnswerEntity> findByQuestionId(Long questionId);

    List<ResumeInterviewAnswerEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
