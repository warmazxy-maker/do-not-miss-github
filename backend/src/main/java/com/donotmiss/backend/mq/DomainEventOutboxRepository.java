package com.donotmiss.backend.mq;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DomainEventOutboxRepository extends JpaRepository<DomainEventOutboxEntity, Long> {
    @Query("""
            select event
            from DomainEventOutboxEntity event
            where event.status in :statuses
              and event.nextAttemptAt <= :now
              and event.attempts < :maxAttempts
            order by event.id asc
            """)
    List<DomainEventOutboxEntity> findPublishable(
            @Param("statuses") Collection<DomainEventOutboxStatus> statuses,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable
    );
}
