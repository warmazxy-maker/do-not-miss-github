package com.donotmiss.backend.challenge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<ChallengeEntity, Long> {
    List<ChallengeEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ChallengeEntity> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, ChallengeStatus status);

    Page<ChallengeEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    Page<ChallengeEntity> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, ChallengeStatus status, Pageable pageable);

    long countByUserIdAndStatus(String userId, ChallengeStatus status);

    Optional<ChallengeEntity> findByIdAndUserId(Long id, String userId);
}
