package com.donotmiss.backend.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventStatusHistoryRepository extends JpaRepository<EventStatusHistoryEntity, Long> {
    List<EventStatusHistoryEntity> findByEventIdOrderByIdAsc(Long eventId);
}
