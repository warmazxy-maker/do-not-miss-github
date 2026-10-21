package com.donotmiss.backend.guide;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GuideSessionRepository extends JpaRepository<GuideSessionEntity, Long> {
    Optional<GuideSessionEntity> findBySessionIdAndUserId(String sessionId, String userId);

    List<GuideSessionEntity> findTop10ByUserIdAndStatusOrderByUpdatedAtDesc(String userId, String status);

    List<GuideSessionEntity> findTop50ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(String status, Instant expiresAt);
}
