package com.donotmiss.backend.achievement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface AchievementRecordRepository extends JpaRepository<AchievementRecordEntity, Long> {
    List<AchievementRecordEntity> findByUserIdOrderByCompletedAtDesc(String userId);

    Page<AchievementRecordEntity> findByUserIdOrderByCompletedAtDesc(String userId, Pageable pageable);

    Optional<AchievementRecordEntity> findByUserIdAndEventId(String userId, Long eventId);

    Optional<AchievementRecordEntity> findByUserIdAndSourceTypeAndSourceId(String userId, AchievementSourceType sourceType, Long sourceId);

    Optional<AchievementRecordEntity> findByIdAndUserId(Long id, String userId);
}
