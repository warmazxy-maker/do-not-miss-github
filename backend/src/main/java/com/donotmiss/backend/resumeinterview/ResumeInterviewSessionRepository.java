package com.donotmiss.backend.resumeinterview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeInterviewSessionRepository extends JpaRepository<ResumeInterviewSessionEntity, Long> {
    Optional<ResumeInterviewSessionEntity> findByIdAndUserId(Long id, String userId);

    Optional<ResumeInterviewSessionEntity> findByRequestIdAndUserId(String requestId, String userId);

    List<ResumeInterviewSessionEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ResumeInterviewSessionEntity> findTop20ByStatusOrderByUpdatedAtAsc(ResumeInterviewSessionStatus status);
}
