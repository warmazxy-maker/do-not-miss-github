package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.event.EventCategory;
import com.donotmiss.backend.event.EventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EventSearchIndexService {
    private static final Logger log = LoggerFactory.getLogger(EventSearchIndexService.class);

    private final EventRepository eventRepository;
    private final OpenAiCompatibleLlmClient llmClient;
    private final RestClient restClient;
    private final boolean enabled;
    private final boolean vectorEnabled;
    private final int embeddingDimensions;
    private final String indexName;

    public EventSearchIndexService(EventRepository eventRepository,
                                   OpenAiCompatibleLlmClient llmClient,
                                   @Value("${app.search.enabled:false}") boolean enabled,
                                   @Value("${app.search.vector-enabled:false}") boolean vectorEnabled,
                                   @Value("${app.ai.embedding-dimensions:1024}") int embeddingDimensions,
                                   @Value("${app.search.base-url:http://localhost:9200}") String baseUrl,
                                   @Value("${app.search.index-name:do_not_miss_events}") String indexName) {
        this.eventRepository = eventRepository;
        this.llmClient = llmClient;
        this.enabled = enabled;
        this.vectorEnabled = vectorEnabled;
        this.embeddingDimensions = Math.max(embeddingDimensions, 1);
        this.indexName = indexName;
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isVectorEnabled() {
        return enabled && vectorEnabled && llmClient.isEnabled();
    }

    public int reindexAll() {
        if (!enabled) {
            return 0;
        }
        ensureIndex();
        List<EventEntity> events = eventRepository.findByExpiredFalse();
        for (EventEntity event : events) {
            index(event);
        }
        return events.size();
    }

    public void index(EventEntity event) {
        if (!enabled || event == null || event.getId() == null) {
            return;
        }
        try {
            indexOrThrow(event);
        } catch (RestClientException ex) {
            log.warn("Failed to index event {} into search engine: {}", event.getId(), ex.getMessage());
        }
    }

    public void indexByIdOrThrow(Long eventId) {
        if (!enabled || eventId == null) {
            return;
        }
        EventEntity event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.isExpired()) {
            deleteOrThrow(eventId);
            return;
        }
        indexOrThrow(event);
    }

    public void indexOrThrow(EventEntity event) {
        if (!enabled || event == null || event.getId() == null) {
            return;
        }
        if (event.isExpired()) {
            deleteOrThrow(event.getId());
            return;
        }
        ensureIndex();
        restClient.put()
                .uri("/{index}/_doc/{id}", indexName, event.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(documentOf(event))
                .retrieve()
                .toBodilessEntity();
    }

    public void delete(Long eventId) {
        if (!enabled || eventId == null) {
            return;
        }
        try {
            deleteOrThrow(eventId);
        } catch (RestClientException ex) {
            log.warn("Failed to delete event {} from search engine: {}", eventId, ex.getMessage());
        }
    }

    public void deleteOrThrow(Long eventId) {
        if (!enabled || eventId == null) {
            return;
        }
        restClient.delete()
                .uri("/{index}/_doc/{id}", indexName, eventId)
                .retrieve()
                .toBodilessEntity();
    }

    public List<Long> searchIds(String query, String category, String benefitType, String location, int limit) {
        return searchHits(query, category, benefitType, location, limit).stream()
                .map(SearchHit::eventId)
                .toList();
    }

    public List<SearchHit> searchHits(String query, String category, String benefitType, String location, int limit) {
        if (!enabled) {
            return List.of();
        }
        try {
            ensureIndex();
            JsonNode response = restClient.post()
                    .uri("/{index}/_search", indexName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(searchBody(query, category, benefitType, location, limit))
                    .retrieve()
                    .body(JsonNode.class);

            return hitsFromResponse(response);
        } catch (RestClientException ex) {
            log.warn("Search engine query failed, falling back to local retrieval: {}", ex.getMessage());
            return List.of();
        }
    }

    public List<Long> vectorSearchIds(String query, int limit) {
        return vectorSearchHits(query, limit).stream()
                .map(SearchHit::eventId)
                .toList();
    }

    public List<SearchHit> vectorSearchHits(String query, int limit) {
        if (!isVectorEnabled() || !isPresent(query)) {
            return List.of();
        }
        return llmClient.embedding(query)
                .filter(this::hasExpectedDimensions)
                .map(vector -> searchVectorHits(vector, limit))
                .orElseGet(List::of);
    }

    private List<SearchHit> searchVectorHits(List<Double> vector, int limit) {
        try {
            ensureIndex();
            JsonNode response = restClient.post()
                    .uri("/{index}/_search", indexName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(vectorSearchBody(vector, limit))
                    .retrieve()
                    .body(JsonNode.class);

            return hitsFromResponse(response);
        } catch (RestClientException ex) {
            log.warn("Vector search query failed, vector retrieval will be skipped: {}", ex.getMessage());
            return List.of();
        }
    }

    private void ensureIndex() {
        try {
            restClient.head()
                    .uri("/{index}", indexName)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            createIndex();
        }
    }

    private void createIndex() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("eventId", Map.of("type", "long"));
        properties.put("title", textField());
        properties.put("organizationName", textField());
        properties.put("category", textField());
        properties.put("categoryCode", Map.of("type", "keyword"));
        properties.put("location", textField());
        properties.put("content", textField());
        properties.put("benefitType", textField());
        properties.put("benefitTypeCode", Map.of("type", "keyword"));
        properties.put("skill", textField());
        properties.put("allText", textField());
        properties.put("moneyAmount", Map.of("type", "double"));
        properties.put("startTime", Map.of("type", "date"));
        properties.put("endTime", Map.of("type", "date"));
        if (isVectorEnabled()) {
            properties.put("embedding", Map.of(
                    "type", "knn_vector",
                    "dimension", embeddingDimensions,
                    "method", Map.of(
                            "name", "hnsw",
                            "space_type", "cosinesimil",
                            "engine", "lucene"
                    )
            ));
        }

        Map<String, Object> indexSettings = new LinkedHashMap<>();
        indexSettings.put("max_ngram_diff", 2);
        if (isVectorEnabled()) {
            indexSettings.put("knn", true);
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("index", indexSettings);
        settings.put("analysis", Map.of(
                "tokenizer", Map.of(
                        "cjk_ngram_tokenizer", Map.of(
                                "type", "ngram",
                                "min_gram", 1,
                                "max_gram", 3,
                                "token_chars", List.of("letter", "digit")
                        )
                ),
                "analyzer", Map.of(
                        "cjk_ngram", Map.of(
                                "type", "custom",
                                "tokenizer", "cjk_ngram_tokenizer",
                                "filter", List.of("lowercase")
                        )
                )
        ));

        restClient.put()
                .uri("/{index}", indexName)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of(
                        "settings", settings,
                        "mappings", Map.of("properties", properties)
                ))
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> searchBody(String query, String category, String benefitType, String location, int limit) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (isPresent(category)) {
            filters.add(Map.of("term", Map.of("categoryCode", normalizeCategoryCode(category))));
        }
        if (isPresent(benefitType)) {
            filters.add(Map.of("term", Map.of("benefitTypeCode", normalizeBenefitTypeCode(benefitType))));
        }
        if (isPresent(location)) {
            filters.add(Map.of("match", Map.of("location", location.trim())));
        }

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", filters);
        if (isPresent(query)) {
            bool.put("must", List.of(Map.of(
                    "multi_match", Map.of(
                            "query", query.trim(),
                            "fields", List.of("title^4", "skill^3", "content^2", "organizationName", "location", "category", "benefitType", "allText"),
                            "type", "best_fields"
                    )
            )));
        } else {
            bool.put("must", List.of(Map.of("match_all", Map.of())));
        }

        return Map.of(
                "size", Math.min(Math.max(limit, 1), 100),
                "query", Map.of("bool", bool)
        );
    }

    private Map<String, Object> vectorSearchBody(List<Double> vector, int limit) {
        return Map.of(
                "size", Math.min(Math.max(limit, 1), 100),
                "_source", List.of("eventId"),
                "query", Map.of(
                        "knn", Map.of(
                                "embedding", Map.of(
                                        "vector", vector,
                                        "k", Math.min(Math.max(limit, 1), 100)
                                )
                        )
                )
        );
    }

    private Map<String, Object> documentOf(EventEntity event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.getId());
        data.put("title", event.getTitle());
        data.put("organizationName", event.getOrganizationName());
        data.put("category", event.getCategory().label());
        data.put("categoryCode", event.getCategory().name());
        data.put("location", event.getLocation());
        data.put("content", event.getContent());
        data.put("benefitType", event.getBenefitType().label());
        data.put("benefitTypeCode", event.getBenefitType().name());
        data.put("skill", event.getSkill());
        data.put("moneyAmount", event.getMoneyAmount() == null ? null : asDouble(event.getMoneyAmount()));
        data.put("startTime", event.getStartTime());
        data.put("endTime", event.getEndTime());
        String allText = allTextOf(event);
        data.put("allText", allText);
        if (isVectorEnabled()) {
            llmClient.embedding(allText)
                    .filter(this::hasExpectedDimensions)
                    .ifPresent(vector -> data.put("embedding", vector));
        }
        return data;
    }

    private String allTextOf(EventEntity event) {
        return String.join(" ",
                event.getTitle(),
                event.getOrganizationName(),
                event.getCategory().label(),
                event.getLocation(),
                event.getContent(),
                event.getBenefitType().label(),
                event.getSkill() == null ? "" : event.getSkill()
        );
    }

    private Map<String, Object> textField() {
        return Map.of("type", "text", "analyzer", "cjk_ngram", "search_analyzer", "cjk_ngram");
    }

    private double asDouble(BigDecimal value) {
        return value.doubleValue();
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasExpectedDimensions(List<Double> vector) {
        if (vector.size() != embeddingDimensions) {
            log.warn("Embedding dimensions mismatch. expected={}, actual={}", embeddingDimensions, vector.size());
            return false;
        }
        return true;
    }

    private List<SearchHit> hitsFromResponse(JsonNode response) {
        List<SearchHit> hitsWithScores = new ArrayList<>();
        JsonNode hits = response == null ? null : response.path("hits").path("hits");
        if (hits != null && hits.isArray()) {
            for (JsonNode hit : hits) {
                long id = hit.path("_source").path("eventId").asLong(0);
                if (id > 0) {
                    hitsWithScores.add(new SearchHit(id, hit.path("_score").asDouble(0)));
                }
            }
        }
        return hitsWithScores;
    }

    private String normalizeCategoryCode(String value) {
        try {
            return EventCategory.fromText(value.trim()).name();
        } catch (RuntimeException ex) {
            return value.trim();
        }
    }

    private String normalizeBenefitTypeCode(String value) {
        try {
            return BenefitType.fromText(value.trim()).name();
        } catch (RuntimeException ex) {
            return value.trim();
        }
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null || value.isBlank() ? "http://localhost:9200" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record SearchHit(Long eventId, double score) {
    }
}
