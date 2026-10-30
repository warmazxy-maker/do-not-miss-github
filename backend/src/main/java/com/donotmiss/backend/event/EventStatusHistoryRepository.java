package com.donotmiss.backend.event;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventStatusHistoryRepository extends JpaRepository<EventStatusHistoryEntity, Long> {
    List<EventStatusHistoryEntity> findByEventIdOrderByIdAsc(Long eventId);

    /** When the event last entered this state, e.g. how long it has been waiting for review. */
    Optional<EventStatusHistoryEntity> findFirstByEventIdAndToStatusOrderByIdDesc(Long eventId, EventReviewStatus toStatus);

    /** Organisation red dot: events of this publisher that the AI or an operator changed since a moment. */
    @Query("""
            select count(distinct h.eventId) from EventStatusHistoryEntity h, EventEntity e
            where e.id = h.eventId and e.createdByUserId = :userId
              and h.actorType in :actorTypes and h.createdAt > :since
            """)
    long countChangedEventsSince(@Param("userId") String userId,
                                 @Param("actorTypes") Collection<EventActorType> actorTypes,
                                 @Param("since") Instant since);
}
