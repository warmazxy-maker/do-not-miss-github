package com.donotmiss.backend.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduleItemRepository extends JpaRepository<ScheduleItemEntity, Long> {
    List<ScheduleItemEntity> findByUserIdAndStatusAndStartTimeBetweenOrderByStartTimeAsc(
            String userId,
            ScheduleItemStatus status,
            LocalDateTime from,
            LocalDateTime to
    );

    List<ScheduleItemEntity> findByUserIdAndStatusOrderByStartTimeAsc(String userId, ScheduleItemStatus status);

    Optional<ScheduleItemEntity> findByIdAndUserId(Long id, String userId);

    Optional<ScheduleItemEntity> findByUserIdAndItemTypeAndSourceId(String userId, ScheduleItemType itemType, Long sourceId);

    List<ScheduleItemEntity> findByUserIdAndItemTypeAndSourceIdOrderByStartTimeAsc(
            String userId,
            ScheduleItemType itemType,
            Long sourceId
    );
}
