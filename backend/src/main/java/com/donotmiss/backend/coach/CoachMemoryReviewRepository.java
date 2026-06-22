package com.donotmiss.backend.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CoachMemoryReviewRepository extends JpaRepository<CoachMemoryReviewEntity, Long> {
    List<CoachMemoryReviewEntity> findTop3ByUserIdAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(String userId, Instant now);

    List<CoachMemoryReviewEntity> findTop20ByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<CoachMemoryReviewEntity> findByUserIdAndSourceLogId(String userId, Long sourceLogId);
}
