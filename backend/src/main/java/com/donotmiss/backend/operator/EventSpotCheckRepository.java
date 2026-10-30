package com.donotmiss.backend.operator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventSpotCheckRepository extends JpaRepository<EventSpotCheckEntity, Long> {
    List<EventSpotCheckEntity> findByStatusOrderByDrawnAtAsc(SpotCheckStatus status);

    Optional<EventSpotCheckEntity> findByEventIdAndStatus(Long eventId, SpotCheckStatus status);
}
