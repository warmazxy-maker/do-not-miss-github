package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventExpirationService;
import com.donotmiss.backend.event.EventService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Production corpus: events in MySQL, searched through the live OpenSearch index. */
@Component
public class LiveEventCorpus implements EventCorpus {
    private final EventService eventService;
    private final EventExpirationService eventExpirationService;
    private final EventSearchIndexService eventSearchIndexService;

    public LiveEventCorpus(EventService eventService,
                           EventExpirationService eventExpirationService,
                           EventSearchIndexService eventSearchIndexService) {
        this.eventService = eventService;
        this.eventExpirationService = eventExpirationService;
        this.eventSearchIndexService = eventSearchIndexService;
    }

    @Override
    public String name() {
        return "live";
    }

    @Override
    public boolean searchEnabled() {
        return eventSearchIndexService.isEnabled();
    }

    @Override
    public List<EventSearchIndexService.SearchHit> bm25Hits(String query, String category, String benefitType, String location, int limit) {
        return eventSearchIndexService.searchHits(query, category, benefitType, location, limit);
    }

    @Override
    public List<EventSearchIndexService.SearchHit> vectorHits(String query, int limit) {
        return eventSearchIndexService.vectorSearchHits(query, limit);
    }

    @Override
    public Optional<EventEntity> find(Long eventId) {
        try {
            return Optional.ofNullable(eventService.getEntity(eventId));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<EventEntity> filtered(String category, String benefitType, String location) {
        return eventService.searchEntities(new EventDtos.EventSearchRequest(null, category, benefitType, location));
    }

    @Override
    public void beforeRetrieve() {
        eventExpirationService.expireOverdueEvents();
    }
}
