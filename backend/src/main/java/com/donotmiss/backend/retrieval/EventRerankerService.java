package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EventRerankerService {
    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int topN;

    public EventRerankerService(OpenAiCompatibleLlmClient llmClient,
                                ObjectMapper objectMapper,
                                @Value("${app.search.rerank-enabled:false}") boolean enabled,
                                @Value("${app.search.rerank-top-n:12}") int topN) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.topN = Math.max(topN, 2);
    }

    public List<RetrievedEvent> rerank(String query, UserMemoryDtos.Profile memory, List<RetrievedEvent> events) {
        if (!enabled || !llmClient.isEnabled() || events.size() < 2) {
            return events;
        }

        List<RetrievedEvent> candidates = events.stream().limit(topN).toList();
        String systemPrompt = """
                You are a strict event reranker.
                You can only reorder the candidate eventIds provided by the backend.
                Do not invent events, organizations, times, places, or skills.
                Prefer events that directly satisfy the student's concrete need, skill target, location, timing, and benefit preference.
                Return strict JSON only.
                JSON format: {"items":[{"eventId":1,"reason":"short evidence-based rerank reason"}]}
                """;

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("query", query);
        context.put("studentMemory", compactMemory(memory));
        context.put("candidates", candidates.stream().map(this::candidateContext).toList());

        return llmClient.chatForJson(systemPrompt, toJson(context), RetrievalDtos.RerankModelResponse.class)
                .map(output -> applyOrder(events, output))
                .filter(reranked -> !reranked.isEmpty())
                .orElse(events);
    }

    private List<RetrievedEvent> applyOrder(List<RetrievedEvent> original, RetrievalDtos.RerankModelResponse output) {
        if (output.items() == null || output.items().isEmpty()) {
            return original;
        }

        Map<Long, RetrievedEvent> byId = original.stream()
                .collect(Collectors.toMap(item -> item.event().getId(), item -> item, (left, right) -> left, LinkedHashMap::new));
        Set<Long> used = new LinkedHashSet<>();
        List<RetrievedEvent> ordered = new ArrayList<>();

        for (RetrievalDtos.RerankModelItem item : output.items()) {
            if (item.eventId() == null || used.contains(item.eventId())) {
                continue;
            }
            RetrievedEvent retrievedEvent = byId.get(item.eventId());
            if (retrievedEvent != null) {
                ordered.add(withRerankEvidence(retrievedEvent, item.reason()));
                used.add(item.eventId());
            }
        }

        for (RetrievedEvent item : original) {
            if (used.add(item.event().getId())) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    private RetrievedEvent withRerankEvidence(RetrievedEvent item, String reason) {
        List<String> evidence = new ArrayList<>(item.evidence());
        if (reason != null && !reason.isBlank()) {
            evidence.add("LLM reranker: " + compact(reason, 100));
        } else {
            evidence.add("LLM reranker: candidate kept after semantic rerank");
        }
        return new RetrievedEvent(
                item.event(),
                item.keywordScore(),
                item.bm25Score(),
                item.semanticScore(),
                item.intentScore(),
                item.memoryScore(),
                item.metadataScore(),
                item.recallScore(),
                item.finalScore(),
                evidence.stream().distinct().limit(6).toList()
        );
    }

    private Map<String, Object> candidateContext(RetrievedEvent item) {
        EventEntity event = item.event();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.getId());
        data.put("title", event.getTitle());
        data.put("organizationName", event.getOrganizationName());
        data.put("category", event.getCategory().label());
        data.put("location", event.getLocation());
        data.put("content", compact(event.getContent(), 220));
        data.put("skill", compact(event.getSkill(), 160));
        data.put("benefitType", event.getBenefitType().label());
        data.put("moneyAmount", event.getMoneyAmount());
        data.put("startTime", event.getStartTime());
        data.put("retrievalScore", item.finalScore());
        data.put("evidence", item.evidence());
        return data;
    }

    private Map<String, Object> compactMemory(UserMemoryDtos.Profile memory) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preferredCategories", memory.preferredCategories());
        data.put("preferredLocations", memory.preferredLocations());
        data.put("benefitPreferences", memory.benefitPreferences());
        data.put("strengths", memory.strengths().stream().limit(6).toList());
        data.put("evidenceKeywords", memory.evidenceKeywords().stream().limit(10).toList());
        return data;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String compact(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }
}
