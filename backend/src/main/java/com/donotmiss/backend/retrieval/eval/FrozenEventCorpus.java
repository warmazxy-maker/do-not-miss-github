package com.donotmiss.backend.retrieval.eval;

import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.event.EventCategory;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.eventquality.EventQualityReportEntity;
import com.donotmiss.backend.retrieval.EventCorpus;
import com.donotmiss.backend.retrieval.EventSearchIndexService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluation corpus read from a versioned fixture. Events live in memory, never in MySQL, and are
 * searched through a dedicated index whose name carries the corpus fingerprint, so the live index
 * and the live tables are never touched and a changed fixture can never reuse a stale index.
 */
public class FrozenEventCorpus implements EventCorpus {
    private final String version;
    private final String fingerprint;
    private final String indexName;
    private final Map<Long, EventEntity> events;
    private final Map<Long, EventQualityReportEntity> qualityReports;
    private final EventSearchIndexService searchIndex;

    public FrozenEventCorpus(String version,
                             String fingerprint,
                             String indexName,
                             List<EventEntity> events,
                             Map<Long, EventQualityReportEntity> qualityReports,
                             EventSearchIndexService searchIndex) {
        this.version = version;
        this.fingerprint = fingerprint;
        this.indexName = indexName;
        Map<Long, EventEntity> byId = new LinkedHashMap<>();
        events.forEach(event -> byId.put(event.getId(), event));
        this.events = Collections.unmodifiableMap(byId);
        this.qualityReports = Map.copyOf(qualityReports);
        this.searchIndex = searchIndex;
    }

    @Override
    public String name() {
        return version;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String indexName() {
        return indexName;
    }

    public int size() {
        return events.size();
    }

    public Optional<EventQualityReportEntity> qualityReport(Long eventId) {
        return Optional.ofNullable(qualityReports.get(eventId));
    }

    @Override
    public boolean searchEnabled() {
        return searchIndex != null && searchIndex.isEnabled();
    }

    @Override
    public List<EventSearchIndexService.SearchHit> bm25Hits(String query, String category, String benefitType, String location, int limit) {
        return searchIndex.searchHits(indexName, query, category, benefitType, location, limit);
    }

    @Override
    public List<EventSearchIndexService.SearchHit> vectorHits(String query, int limit) {
        return searchIndex.vectorSearchHits(indexName, query, limit);
    }

    @Override
    public Optional<EventEntity> find(Long eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    /** Same filter semantics as the live database query: exact category / benefit type, location substring. */
    @Override
    public List<EventEntity> filtered(String category, String benefitType, String location) {
        return events.values().stream()
                .filter(event -> category == null || category.isBlank() || event.getCategory() == parseCategory(category))
                .filter(event -> benefitType == null || benefitType.isBlank() || event.getBenefitType() == parseBenefit(benefitType))
                .filter(event -> location == null || location.isBlank()
                        || event.getLocation().toLowerCase(Locale.ROOT).contains(location.trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

    public List<EventSearchIndexService.IndexedEvent> indexedEvents() {
        return events.values().stream()
                .map(event -> new EventSearchIndexService.IndexedEvent(event, qualityReports.get(event.getId())))
                .toList();
    }

    private static EventCategory parseCategory(String value) {
        try {
            return EventCategory.fromText(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static BenefitType parseBenefit(String value) {
        try {
            return BenefitType.fromText(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
