package com.donotmiss.backend.event;

import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EventExpirationService {
    private final EventRepository eventRepository;
    private final DomainEventPublisher domainEventPublisher;

    public EventExpirationService(EventRepository eventRepository,
                                  DomainEventPublisher domainEventPublisher) {
        this.eventRepository = eventRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Scheduled(
            initialDelayString = "${app.events.expiration.initial-delay-ms:60000}",
            fixedDelayString = "${app.events.expiration.delay-ms:300000}"
    )
    @Transactional
    public int expireOverdueEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<EventEntity> overdueEvents = eventRepository.findByExpiredFalseAndEndTimeBefore(now);
        overdueEvents.forEach(event -> {
            event.setExpired(true);
            domainEventPublisher.publishEventIndex(event.getId(), DomainEventMessages.EVENT_INDEX_DELETE);
        });
        eventRepository.saveAll(overdueEvents);
        return overdueEvents.size();
    }
}
