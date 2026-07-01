package com.donotmiss.backend.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, Long>, JpaSpecificationExecutor<EventEntity> {
    List<EventEntity> findByExpiredFalse();

    List<EventEntity> findByExpiredFalseAndReviewStatus(EventReviewStatus reviewStatus);

    List<EventEntity> findByExpiredFalseAndEndTimeBefore(LocalDateTime now);

    List<EventEntity> findByCreatedByUserIdOrderByCreatedAtDesc(String userId);
}
