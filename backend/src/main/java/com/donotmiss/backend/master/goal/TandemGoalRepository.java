package com.donotmiss.backend.master.goal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TandemGoalRepository extends JpaRepository<TandemGoalEntity, Long> {
    Optional<TandemGoalEntity> findByIdAndUserId(Long id, String userId);

    List<TandemGoalEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    long countByUserIdAndStatus(String userId, TandemGoalEntity.Status status);
}
