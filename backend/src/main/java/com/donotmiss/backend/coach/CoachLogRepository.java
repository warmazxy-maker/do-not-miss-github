package com.donotmiss.backend.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CoachLogRepository extends JpaRepository<CoachLogEntity, Long> {
    List<CoachLogEntity> findByUserIdOrderByLogDateDesc(String userId);

    Optional<CoachLogEntity> findByUserIdAndLogDate(String userId, LocalDate logDate);

    long countByUserId(String userId);
}
