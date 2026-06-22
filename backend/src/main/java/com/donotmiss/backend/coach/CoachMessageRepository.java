package com.donotmiss.backend.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CoachMessageRepository extends JpaRepository<CoachMessageEntity, Long> {
    List<CoachMessageEntity> findByUserIdAndMessageDateOrderByCreatedAtAsc(String userId, LocalDate messageDate);
}
