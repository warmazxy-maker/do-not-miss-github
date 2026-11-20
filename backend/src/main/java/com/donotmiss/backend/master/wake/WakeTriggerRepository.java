package com.donotmiss.backend.master.wake;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface WakeTriggerRepository extends JpaRepository<WakeTriggerEntity, Long> {
    boolean existsByUserIdAndDedupeKey(String userId, String dedupeKey);

    @Query("select distinct t.userId from WakeTriggerEntity t where t.status = :status and t.processAfter <= :now")
    List<String> usersWithDue(@Param("status") WakeTriggerStatus status, @Param("now") Instant now);

    List<WakeTriggerEntity> findByUserIdAndStatusAndProcessAfterLessThanEqualOrderByIdAsc(
            String userId, WakeTriggerStatus status, Instant before);

    /** Compare-and-set, so two processors never handle the same trigger. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WakeTriggerEntity t set t.status = :to where t.id = :id and t.status = :from")
    int transition(@Param("id") Long id, @Param("from") WakeTriggerStatus from, @Param("to") WakeTriggerStatus to);

    long countByStatusAndProcessedAtAfter(WakeTriggerStatus status, Instant after);

    long countByStatus(WakeTriggerStatus status);
}
