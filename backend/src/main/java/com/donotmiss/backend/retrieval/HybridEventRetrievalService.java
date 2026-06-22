package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventCategory;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventExpirationService;
import com.donotmiss.backend.event.EventService;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.mcp.McpDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class HybridEventRetrievalService {
    private static final int DEFAULT_LIMIT = 20;
    private static final List<String> INTENT_WORDS = List.of(
            "日语", "英语", "翻译", "沟通", "运营", "现场", "执行", "协作", "研究", "调研",
            "访谈", "问卷", "数据", "文案", "写作", "线上", "报酬", "兼职", "健身", "运动",
            "java", "spring", "后端", "编程", "程序设计", "开发", "项目", "外卖", "微信"
    );

    private static final List<String> SPECIFIC_QUERY_WORDS = List.of(
            "ai", "agent", "llm", "rag", "java", "spring", "python", "go",
            "日语", "英语", "翻译", "研究", "调研", "问卷", "访谈", "健身", "运动",
            "报酬", "兼职", "线上", "项目", "开发", "大模型", "人工智能"
    );
    private static final List<String> VAGUE_QUERY_WORDS = List.of(
            "推荐", "活动", "机会", "都行", "随便", "不知道", "找点", "看看", "有啥"
    );
    private static final List<String> GENERIC_QUERY_TERMS = List.of(
            "unknown", "skill", "money", "both", "活动", "机会", "推荐", "学习", "提升", "成长", "经验", "适合", "参与"
    );

    private final EventService eventService;
    private final EventExpirationService eventExpirationService;
    private final EventSearchIndexService eventSearchIndexService;
    private final EventRerankerService eventRerankerService;

    public HybridEventRetrievalService(EventService eventService,
                                       EventExpirationService eventExpirationService,
                                       EventSearchIndexService eventSearchIndexService,
                                       EventRerankerService eventRerankerService) {
        this.eventService = eventService;
        this.eventExpirationService = eventExpirationService;
        this.eventSearchIndexService = eventSearchIndexService;
        this.eventRerankerService = eventRerankerService;
    }

    @Transactional(readOnly = true)
    public List<RetrievedEvent> retrieve(AiDtos.EventRecommendationRequest request, UserMemoryDtos.Profile memory) {
        return retrieve(request.need(), request.category(), request.benefitType(), request.location(), memory, DEFAULT_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<RetrievedEvent> retrieve(AiDtos.EventRecommendationRequest request,
                                         UserMemoryDtos.Profile memory,
                                         McpDtos.ToolContextResponse toolContext) {
        return retrieve(request.need(), request.category(), request.benefitType(), request.location(), memory, DEFAULT_LIMIT, toolContext);
    }

    @Transactional(readOnly = true)
    public List<RetrievedEvent> retrieve(String query,
                                         String category,
                                         String benefitType,
                                         String location,
                                         UserMemoryDtos.Profile memory,
                                         int limit) {
        return retrieve(query, category, benefitType, location, memory, limit, null);
    }

    @Transactional(readOnly = true)
    public List<RetrievedEvent> retrieve(String query,
                                         String category,
                                         String benefitType,
                                         String location,
                                         UserMemoryDtos.Profile memory,
                                         int limit,
                                         McpDtos.ToolContextResponse toolContext) {
        eventExpirationService.expireOverdueEvents();
        CandidatePool candidatePool = searchEngineCandidates(query, category, benefitType, location, limit);
        List<EventEntity> candidates = candidatePool.events();
        Map<Long, Double> bm25Scores = candidatePool.bm25Scores();
        Map<Long, Double> semanticScores = candidatePool.semanticScores();
        Map<Long, Double> recallScores = candidatePool.recallScores();
        if (candidates.isEmpty()) {
            candidates = databaseCandidates(category, benefitType, location);
            bm25Scores = Map.of();
            semanticScores = Map.of();
            recallScores = Map.of();
        }

        if (candidates.isEmpty() && !hasHardFilter(category, benefitType, location)) {
            candidates = eventService.searchEntities(new EventDtos.EventSearchRequest(null, null, null, null));
            bm25Scores = Map.of();
            semanticScores = Map.of();
            recallScores = Map.of();
        }

        List<String> queryTerms = tokenize(query);
        List<String> memoryTerms = memoryTerms(memory);
        ScoringWeights scoringWeights = adaptiveWeights(query, queryTerms);
        Map<String, Integer> documentFrequency = documentFrequency(candidates);
        int documentCount = candidates.size();
        Map<Long, Double> candidateBm25Scores = bm25Scores;
        Map<Long, Double> candidateSemanticScores = semanticScores;
        Map<Long, Double> candidateRecallScores = recallScores;

        List<RetrievedEvent> scoredEvents = candidates.stream()
                .map(event -> score(event, queryTerms, memoryTerms, memory, documentFrequency, documentCount,
                        candidateBm25Scores.getOrDefault(event.getId(), 0.0),
                        candidateSemanticScores.getOrDefault(event.getId(), 0.0),
                        candidateRecallScores.getOrDefault(event.getId(), 0.0), toolContext, scoringWeights))
                .sorted(Comparator.comparingDouble(RetrievedEvent::finalScore).reversed()
                        .thenComparing(item -> item.event().getStartTime()))
                .limit(Math.max(limit * 2, limit))
                .toList();

        return eventRerankerService.rerank(query, memory, scoredEvents).stream()
                .limit(Math.max(limit, 1))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RetrievedEvent> retrieveBaseline(String query,
                                                 String category,
                                                 String benefitType,
                                                 String location,
                                                 UserMemoryDtos.Profile memory,
                                                 int limit,
                                                 McpDtos.ToolContextResponse toolContext) {
        List<EventEntity> candidates = databaseCandidates(category, benefitType, location);
        if (candidates.isEmpty() && !hasHardFilter(category, benefitType, location)) {
            candidates = eventService.searchEntities(new EventDtos.EventSearchRequest(null, null, null, null));
        }

        List<String> queryTerms = tokenize(query);
        List<String> memoryTerms = memoryTerms(memory);
        Map<String, Integer> documentFrequency = documentFrequency(candidates);
        int documentCount = candidates.size();

        return candidates.stream()
                .map(event -> scoreBaseline(event, queryTerms, memoryTerms, memory, documentFrequency, documentCount, toolContext))
                .sorted(Comparator.comparingDouble(RetrievedEvent::finalScore).reversed()
                        .thenComparing(item -> item.event().getStartTime()))
                .limit(Math.max(limit, 1))
                .toList();
    }

    private CandidatePool searchEngineCandidates(String query, String category, String benefitType, String location, int limit) {
        if (!eventSearchIndexService.isEnabled()) {
            return CandidatePool.empty();
        }
        int recallLimit = Math.max(limit * 4, 40);
        List<EventSearchIndexService.SearchHit> bm25Hits = eventSearchIndexService.searchHits(query, blankToNull(category), blankToNull(benefitType), blankToNull(location), recallLimit);
        List<EventSearchIndexService.SearchHit> vectorHits = eventSearchIndexService.vectorSearchHits(query, recallLimit);
        List<Long> fusedIds = fuseRankedHits(bm25Hits, vectorHits, recallLimit);
        Map<Long, Double> bm25Scores = normalizeScores(bm25Hits);
        Map<Long, Double> semanticScores = normalizeScores(vectorHits);
        Map<Long, Double> recallScores = recallScores(fusedIds);

        List<EventEntity> events = fusedIds.stream()
                .map(this::safeGetEvent)
                .filter(event -> event != null)
                .filter(event -> !event.isExpired())
                .filter(event -> matchesFilters(event, category, benefitType, location))
                .toList();
        return new CandidatePool(events, bm25Scores, semanticScores, recallScores);
    }

    private List<Long> fuseRankedHits(List<EventSearchIndexService.SearchHit> bm25Hits,
                                      List<EventSearchIndexService.SearchHit> vectorHits,
                                      int limit) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        addRankFusionScores(scores, hitIds(bm25Hits), 1.0);
        addRankFusionScores(scores, hitIds(vectorHits), 1.15);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(Math.max(limit, 1))
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<Long> hitIds(List<EventSearchIndexService.SearchHit> hits) {
        return hits.stream()
                .map(EventSearchIndexService.SearchHit::eventId)
                .toList();
    }

    private Map<Long, Double> normalizeScores(List<EventSearchIndexService.SearchHit> hits) {
        if (hits.isEmpty()) {
            return Map.of();
        }
        double maxScore = hits.stream()
                .mapToDouble(EventSearchIndexService.SearchHit::score)
                .max()
                .orElse(0);
        Map<Long, Double> scores = new LinkedHashMap<>();
        int rank = 1;
        for (EventSearchIndexService.SearchHit hit : hits) {
            if (hit.eventId() == null) {
                rank += 1;
                continue;
            }
            double normalized = maxScore > 0
                    ? Math.min(100, Math.max(0, hit.score() / maxScore * 100))
                    : Math.max(0, 100 - (rank - 1) * 4.0);
            scores.merge(hit.eventId(), normalized, Math::max);
            rank += 1;
        }
        return scores;
    }

    private void addRankFusionScores(Map<Long, Double> scores, List<Long> rankedIds, double weight) {
        int rank = 1;
        for (Long id : rankedIds) {
            if (id != null) {
                scores.merge(id, weight / (60 + rank), Double::sum);
            }
            rank += 1;
        }
    }

    private Map<Long, Double> recallScores(List<Long> fusedIds) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        int rank = 1;
        for (Long id : fusedIds) {
            if (id != null) {
                scores.put(id, Math.max(0, 100 - (rank - 1) * 4.0));
            }
            rank += 1;
        }
        return scores;
    }

    private boolean matchesFilters(EventEntity event, String category, String benefitType, String location) {
        if (event.isExpired()) {
            return false;
        }
        if (blankToNull(category) != null && !matchesCategory(event, category)) {
            return false;
        }
        if (blankToNull(benefitType) != null && !matchesBenefitType(event, benefitType)) {
            return false;
        }
        if (blankToNull(location) != null && !event.getLocation().toLowerCase(Locale.ROOT).contains(location.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return true;
    }

    private boolean matchesCategory(EventEntity event, String category) {
        try {
            return event.getCategory() == EventCategory.fromText(category);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private boolean matchesBenefitType(EventEntity event, String benefitType) {
        try {
            return event.getBenefitType() == BenefitType.fromText(benefitType);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private List<EventEntity> databaseCandidates(String category, String benefitType, String location) {
        return eventService.searchEntities(new EventDtos.EventSearchRequest(
                null,
                blankToNull(category),
                blankToNull(benefitType),
                blankToNull(location)
        ));
    }

    private EventEntity safeGetEvent(Long eventId) {
        try {
            return eventService.getEntity(eventId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private RetrievedEvent score(EventEntity event,
                                 List<String> queryTerms,
                                 List<String> memoryTerms,
                                 UserMemoryDtos.Profile memory,
                                 Map<String, Integer> documentFrequency,
                                 int documentCount,
                                 double bm25Score,
                                 double semanticScore,
                                 double recallScore,
                                 McpDtos.ToolContextResponse toolContext,
                                 ScoringWeights weights) {
        String text = textOf(event).toLowerCase(Locale.ROOT);
        double keywordScore = bm25LikeScore(text, queryTerms, documentFrequency, documentCount);
        double intentScore = intentScore(text, queryTerms);
        double memoryScore = memoryScore(event, text, memoryTerms, memory);
        double metadataScore = metadataScore(event, memory, toolContext);
        boolean expired = isExpired(event, toolContext);
        double finalScore = keywordScore * weights.keywordWeight()
                + bm25Score * weights.bm25Weight()
                + semanticScore * weights.semanticWeight()
                + intentScore * weights.intentWeight()
                + memoryScore * weights.memoryWeight()
                + metadataScore * weights.metadataWeight()
                + recallScore * weights.recallWeight();
        if (expired) {
            finalScore = 0;
        }

        List<String> evidence = buildEvidence(event, queryTerms, memoryTerms, memory, keywordScore, bm25Score, semanticScore, intentScore, metadataScore, recallScore, expired, toolContext, weights);
        return new RetrievedEvent(event, round(keywordScore), round(bm25Score), round(semanticScore), round(intentScore), round(memoryScore), round(metadataScore), round(recallScore), round(finalScore), evidence);
    }

    private RetrievedEvent scoreBaseline(EventEntity event,
                                         List<String> queryTerms,
                                         List<String> memoryTerms,
                                         UserMemoryDtos.Profile memory,
                                         Map<String, Integer> documentFrequency,
                                         int documentCount,
                                         McpDtos.ToolContextResponse toolContext) {
        String text = textOf(event).toLowerCase(Locale.ROOT);
        double keywordScore = bm25LikeScore(text, queryTerms, documentFrequency, documentCount);
        double intentScore = intentScore(text, queryTerms);
        double memoryScore = memoryScore(event, text, memoryTerms, memory);
        double metadataScore = metadataScore(event, memory, toolContext);
        boolean expired = isExpired(event, toolContext);
        double finalScore = keywordScore * 0.44
                + intentScore * 0.22
                + memoryScore * 0.22
                + metadataScore * 0.12;
        if (expired) {
            finalScore = 0;
        }

        List<String> evidence = buildEvidence(event, queryTerms, memoryTerms, memory, keywordScore, 0, 0, intentScore, metadataScore, 0, expired, toolContext, ScoringWeights.fixedBaseline());
        return new RetrievedEvent(event, round(keywordScore), 0, 0, round(intentScore), round(memoryScore), round(metadataScore), 0, round(finalScore), evidence);
    }

    private double bm25LikeScore(String text, List<String> terms, Map<String, Integer> documentFrequency, int documentCount) {
        if (terms.isEmpty()) {
            return 0;
        }

        double score = 0;
        for (String term : terms) {
            if (term.length() < 2) {
                continue;
            }
            int termFrequency = countOccurrences(text, term);
            if (termFrequency == 0) {
                continue;
            }
            int df = Math.max(documentFrequency.getOrDefault(term, 1), 1);
            double idf = Math.log(1 + ((double) documentCount - df + 0.5) / (df + 0.5));
            score += idf * Math.min(termFrequency, 4);
        }
        return Math.min(score * 12, 100);
    }

    private double intentScore(String text, List<String> queryTerms) {
        double score = 0;
        Set<String> matched = new LinkedHashSet<>();
        List<String> intentWords = new ArrayList<>(INTENT_WORDS);
        intentWords.addAll(List.of("ai", "agent", "llm", "大模型", "人工智能"));
        for (String intent : intentWords) {
            if (queryTerms.stream().anyMatch(term -> term.contains(intent) || intent.contains(term)) && text.contains(intent)) {
                matched.add(intent);
            }
        }
        score += matched.size() * 18;
        return Math.min(score, 100);
    }

    private double memoryScore(EventEntity event, String text, List<String> memoryTerms, UserMemoryDtos.Profile memory) {
        double score = 0;
        for (String term : memoryTerms) {
            if (term.length() >= 2 && text.contains(term.toLowerCase(Locale.ROOT))) {
                score += 8;
            }
        }
        if (memory.preferredCategories().contains(event.getCategory().label())) {
            score += 20;
        }
        if (memory.preferredLocations().stream().anyMatch(location -> event.getLocation().contains(location) || location.contains(event.getLocation()))) {
            score += 14;
        }
        return Math.min(score, 100);
    }

    private double metadataScore(EventEntity event, UserMemoryDtos.Profile memory, McpDtos.ToolContextResponse toolContext) {
        double score = 0;
        if (event.getMoneyAmount() != null && memory.benefitPreferences().contains("金钱报酬")) {
            score += 18;
        }
        if (event.getSkill() != null && !event.getSkill().isBlank() && memory.benefitPreferences().contains("技能/经验")) {
            score += 18;
        }
        if (event.getLocation().contains("线上")) {
            score += 8;
        }

        if (toolContext != null && toolContext.currentTime() != null) {
            LocalDateTime now = toolContext.currentTime().serverTime().toLocalDateTime();
            if (event.getStartTime().isBefore(now)) {
                score -= 24;
            } else {
                score += 14;
                if (event.getStartTime().toLocalDate().isEqual(now.toLocalDate())) {
                    score += 10;
                } else if (event.getStartTime().isBefore(now.plusDays(30))) {
                    score += 8;
                }
            }
        }

        String locationQuery = locationQuery(toolContext).toLowerCase(Locale.ROOT);
        if (!locationQuery.isBlank()) {
            String eventLocation = event.getLocation().toLowerCase(Locale.ROOT);
            if (locationQuery.contains(eventLocation) || eventLocation.contains(locationQuery)) {
                score += 16;
            } else if (tokenize(locationQuery).stream().anyMatch(term -> term.length() >= 2 && eventLocation.contains(term))) {
                score += 10;
            }
        }

        return Math.min(Math.max(score, 0), 100);
    }

    private boolean isExpired(EventEntity event, McpDtos.ToolContextResponse toolContext) {
        if (event.isExpired()) {
            return true;
        }
        if (toolContext == null || toolContext.currentTime() == null) {
            return false;
        }
        LocalDateTime now = toolContext.currentTime().serverTime().toLocalDateTime();
        LocalDateTime effectiveEnd = event.getEndTime() == null ? event.getStartTime() : event.getEndTime();
        return effectiveEnd != null && effectiveEnd.isBefore(now);
    }

    private List<String> buildEvidence(EventEntity event,
                                       List<String> queryTerms,
                                       List<String> memoryTerms,
                          UserMemoryDtos.Profile memory,
                          double keywordScore,
                          double bm25Score,
                          double semanticScore,
                          double intentScore,
                          double metadataScore,
                          double recallScore,
                          boolean expired,
                          McpDtos.ToolContextResponse toolContext,
                          ScoringWeights weights) {
        List<String> evidence = new ArrayList<>();
        String text = textOf(event).toLowerCase(Locale.ROOT);
        if (expired) {
            evidence.add("活动已过期，最终检索分置为 0");
        }
        List<String> matchedQuery = queryTerms.stream()
                .filter(term -> term.length() >= 2 && text.contains(term.toLowerCase(Locale.ROOT)))
                .distinct()
                .limit(3)
                .toList();
        if (!matchedQuery.isEmpty()) {
            evidence.add("BM25关键词匹配：" + String.join("、", matchedQuery));
        }
        List<String> matchedMemory = memoryTerms.stream()
                .filter(term -> term.length() >= 2 && text.contains(term.toLowerCase(Locale.ROOT)))
                .distinct()
                .limit(2)
                .toList();
        if (!matchedMemory.isEmpty()) {
            evidence.add("用户画像匹配：" + String.join("、", matchedMemory));
        }
        if (memory.preferredCategories().contains(event.getCategory().label())) {
            evidence.add("历史偏好类别：" + event.getCategory().label());
        }
        if (weights != null && !weights.baseline()) {
            evidence.add("用户画像权重自适应：" + weights.label());
        }
        if (bm25Score > 0) {
            evidence.add("OpenSearch BM25 关键词分：" + round(bm25Score));
        }
        if (semanticScore > 0) {
            evidence.add("OpenSearch 向量语义分：" + round(semanticScore));
        }
        if (intentScore > 0) {
            evidence.add("语义意图匹配");
        }
        if (metadataScore > 0) {
            evidence.add("收益/地点等结构化字段匹配");
        }
        if (recallScore > 0) {
            evidence.add("OpenSearch BM25/向量融合召回加权");
        }
        if (toolContext != null && toolContext.currentTime() != null) {
            evidence.add("MCP时间工具：当前日期 " + toolContext.currentTime().localDate());
        }
        String locationQuery = locationQuery(toolContext);
        if (!locationQuery.isBlank() && event.getLocation().contains(locationQuery)) {
            evidence.add("MCP定位工具匹配：" + locationQuery);
        }
        if (keywordScore <= 0 && evidence.isEmpty()) {
            evidence.add("结构化召回候选，相关性较弱");
        }
        return evidence.stream().distinct().limit(5).toList();
    }

    private Map<String, Integer> documentFrequency(List<EventEntity> candidates) {
        Map<String, Integer> df = new LinkedHashMap<>();
        for (EventEntity event : candidates) {
            Set<String> terms = new LinkedHashSet<>(tokenize(textOf(event)));
            for (String term : terms) {
                if (term.length() >= 2) {
                    df.merge(term, 1, Integer::sum);
                }
            }
        }
        return df;
    }

    private ScoringWeights adaptiveWeights(String query, List<String> queryTerms) {
        QueryClarity clarity = queryClarity(query, queryTerms);
        return switch (clarity) {
            case HIGH -> new ScoringWeights(
                    0.25,
                    0.16,
                    0.27,
                    0.14,
                    0.03,
                    0.10,
                    0.05,
                    "明确需求：提高 OpenSearch 语义分占比，降低历史画像干扰",
                    false
            );
            case MEDIUM -> new ScoringWeights(
                    0.23,
                    0.14,
                    0.25,
                    0.13,
                    0.10,
                    0.10,
                    0.05,
                    "中等明确：平衡语义召回、关键词和用户画像",
                    false
            );
            case LOW -> new ScoringWeights(
                    0.16,
                    0.10,
                    0.20,
                    0.10,
                    0.22,
                    0.14,
                    0.08,
                    "模糊需求：保留用户画像，但仍让语义召回参与排序",
                    false
            );
        };
    }

    private QueryClarity queryClarity(String query, List<String> queryTerms) {
        String normalized = nullToEmpty(query).toLowerCase(Locale.ROOT);
        long meaningfulTermCount = queryTerms.stream()
                .filter(this::isMeaningfulQueryTerm)
                .count();
        boolean hasSpecificSignal = SPECIFIC_QUERY_WORDS.stream()
                .anyMatch(word -> normalized.contains(word.toLowerCase(Locale.ROOT)));
        boolean hasVagueSignal = VAGUE_QUERY_WORDS.stream()
                .anyMatch(word -> normalized.contains(word.toLowerCase(Locale.ROOT)));

        if (hasVagueSignal && !hasSpecificSignal && meaningfulTermCount <= 4) {
            return QueryClarity.LOW;
        }
        if (hasSpecificSignal || meaningfulTermCount >= 8) {
            return QueryClarity.HIGH;
        }
        return QueryClarity.MEDIUM;
    }

    private boolean isMeaningfulQueryTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String normalized = term.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2) {
            return false;
        }
        return GENERIC_QUERY_TERMS.stream()
                .noneMatch(generic -> normalized.equals(generic.toLowerCase(Locale.ROOT)));
    }

    private List<String> memoryTerms(UserMemoryDtos.Profile memory) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.addAll(memory.evidenceKeywords());
        terms.addAll(memory.preferredCategories());
        terms.addAll(memory.preferredLocations());
        terms.addAll(memory.strengths());
        return terms.stream()
                .flatMap(value -> tokenize(value).stream())
                .filter(term -> term.length() >= 2)
                .limit(24)
                .toList();
    }

    public List<String> tokenize(String value) {
        String normalized = blankToNull(value) == null ? "" : value.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : normalized.split("[\\s,，;；、。.!！?？:：/\\\\()（）\\[\\]【】]+")) {
            if (!part.isBlank()) {
                terms.add(part);
                if (containsCjk(part) && part.length() > 2) {
                    for (int i = 0; i < part.length() - 1; i++) {
                        terms.add(part.substring(i, i + 2));
                    }
                }
            }
        }
        return terms.stream().toList();
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) >= 0) {
            count += 1;
            index += Math.max(term.length(), 1);
        }
        return count;
    }

    private boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String textOf(EventEntity event) {
        return String.join(" ",
                event.getTitle(),
                event.getOrganizationName(),
                event.getCategory().label(),
                event.getLocation(),
                event.getContent(),
                event.getBenefitType().label(),
                event.getSkill() == null ? "" : event.getSkill(),
                event.getMoneyAmount() == null ? "" : event.getMoneyAmount().toPlainString()
        );
    }

    private boolean hasHardFilter(String category, String benefitType, String location) {
        return blankToNull(category) != null || blankToNull(benefitType) != null || blankToNull(location) != null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String locationQuery(McpDtos.ToolContextResponse toolContext) {
        if (toolContext == null || toolContext.location() == null || toolContext.location().queryText() == null) {
            return "";
        }
        return toolContext.location().queryText();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private enum QueryClarity {
        HIGH,
        MEDIUM,
        LOW
    }

    private record ScoringWeights(
            double keywordWeight,
            double bm25Weight,
            double semanticWeight,
            double intentWeight,
            double memoryWeight,
            double metadataWeight,
            double recallWeight,
            String label,
            boolean baseline
    ) {
        static ScoringWeights fixedBaseline() {
            return new ScoringWeights(0.44, 0, 0, 0.22, 0.22, 0.12, 0, "baseline fixed weights", true);
        }
    }

    private record CandidatePool(
            List<EventEntity> events,
            Map<Long, Double> bm25Scores,
            Map<Long, Double> semanticScores,
            Map<Long, Double> recallScores
    ) {
        static CandidatePool empty() {
            return new CandidatePool(List.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
