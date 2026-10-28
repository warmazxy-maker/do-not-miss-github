package com.donotmiss.backend.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, Long>, JpaSpecificationExecutor<EventEntity> {
    /** Published and not yet ended: what students can find. */
    List<EventEntity> findByReviewStatusAndEndTimeAfter(EventReviewStatus reviewStatus, LocalDateTime now);

    /** Published but already ended: what the expiry job takes offline. */
    List<EventEntity> findByReviewStatusAndEndTimeLessThanEqual(EventReviewStatus reviewStatus, LocalDateTime now);

    List<EventEntity> findByCreatedByUserIdOrderByCreatedAtDesc(String userId);
}
