package com.donotmiss.backend.eventquality;

import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import com.donotmiss.backend.event.EventReviewStatus;
import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class EventQualityService {
    private static final int MAX_LIST_SIZE = 8;

    private final EventRepository eventRepository;
    private final EventQualityReportRepository reportRepository;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AgentRunService agentRunService;
    private final DomainEventPublisher domainEventPublisher;

    public EventQualityService(EventRepository eventRepository,
                               EventQualityReportRepository reportRepository,
                               OpenAiCompatibleLlmClient llmClient,
                               ObjectMapper objectMapper,
                               AgentRunService agentRunService,
                               DomainEventPublisher domainEventPublisher) {
        this.eventRepository = eventRepository;
        this.reportRepository = reportRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.agentRunService = agentRunService;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Transactional
    public boolean analyzeById(Long eventId) {
        EventEntity event = eventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return false;
        }

        Long runId = agentRunService.startRun(
                event.getCreatedByUserId(),
                AgentRunType.EVENT_QUALITY_ANALYSIS,
                event.getTitle(),
                "eventId=" + event.getId() + ", title=" + event.getTitle()
        );
        try {
            Long extractionStepId = agentRunService.startStep(
                    runId,
                    AgentStepName.EVENT_QUALITY_EXTRACTION,
                    "Analyze event quality and extract structured metadata."
            );
            AnalysisResult analysis = analyzeEvent(event);
            agentRunService.completeStep(extractionStepId,
                    "mode=" + analysis.modelName() + ", score=" + analysis.qualityScore()
                            + ", suggestion=" + analysis.reviewSuggestion());

            Long validationStepId = agentRunService.startStep(
                    runId,
                    AgentStepName.EVENT_QUALITY_VALIDATION,
                    "Clamp fields, detect duplicate events and persist quality report."
            );
            EventQualityReportEntity report = upsertReport(event, analysis);
            EventReviewStatus status = applyReviewStatus(event, report);
            agentRunService.completeStep(validationStepId,
                    "reportId=" + report.getId() + ", reviewStatus=" + status.name()
                            + ", duplicateEventIds=" + report.getDuplicateEventIdsJson());

            Long indexStepId = agentRunService.startStep(
                    runId,
                    AgentStepName.EVENT_QUALITY_INDEX_TRIGGER,
                    "Publish search index message based on review status."
            );
            if (status == EventReviewStatus.APPROVED && !event.isExpired()) {
                domainEventPublisher.publishEventIndex(event.getId(), DomainEventMessages.EVENT_INDEX_UPSERT);
                agentRunService.completeStep(indexStepId, "approved event queued for OpenSearch upsert");
            } else {
                domainEventPublisher.publishEventIndex(event.getId(), DomainEventMessages.EVENT_INDEX_DELETE);
                agentRunService.completeStep(indexStepId, "non-approved or expired event queued for OpenSearch delete");
            }

            agentRunService.finishRun(runId,
                    "qualityScore=" + report.getQualityScore() + ", reviewStatus=" + status.name());
            return true;
        } catch (RuntimeException | Error ex) {
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public EventQualityDtos.ReportResponse getReport(Long eventId) {
        EventQualityReportEntity report = reportRepository.findByEventId(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Event quality report not found."));
        return toResponse(report);
    }

    @Transactional
    public void requestReanalysis(Long eventId) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("事件不存在：" + eventId));
        event.setReviewStatus(EventReviewStatus.PENDING_REVIEW);
        domainEventPublisher.publishEventQualityAnalysis(event.getId());
    }

    @Transactional
    public EventReviewStatus approve(Long eventId) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("事件不存在：" + eventId));
        event.setReviewStatus(EventReviewStatus.APPROVED);
        if (!event.isExpired()) {
            domainEventPublisher.publishEventIndex(event.getId(), DomainEventMessages.EVENT_INDEX_UPSERT);
        }
        return event.getReviewStatus();
    }

    @Transactional
    public EventReviewStatus reject(Long eventId) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("事件不存在：" + eventId));
        event.setReviewStatus(EventReviewStatus.REJECTED);
        domainEventPublisher.publishEventIndex(event.getId(), DomainEventMessages.EVENT_INDEX_DELETE);
        return event.getReviewStatus();
    }

    @Transactional(readOnly = true)
    public Optional<EventQualityReportEntity> findReport(Long eventId) {
        return reportRepository.findByEventId(eventId);
    }

    public EventQualityDtos.ReportResponse toResponse(EventQualityReportEntity report) {
        return new EventQualityDtos.ReportResponse(
                report.getId(),
                report.getEventId(),
                report.getQualityScore(),
                report.getQualityLevel(),
                report.getReviewSuggestion(),
                report.getDifficulty(),
                report.getSummary(),
                readStringList(report.getTargetStudentsJson()),
                readStringList(report.getPrerequisitesJson()),
                readStringList(report.getLearningOutcomesJson()),
                readStringList(report.getExtractedTagsJson()),
                readAbilityImpacts(report.getAbilityImpactsJson()),
                readStringList(report.getRiskFlagsJson()),
                readStringList(report.getMissingFieldsJson()),
                readLongList(report.getDuplicateEventIdsJson()),
                report.getModelName(),
                report.getConfidence(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }

    private AnalysisResult analyzeEvent(EventEntity event) {
        if (llmClient.isEnabled()) {
            Optional<AnalysisResult> modelResult = modelAnalysis(event);
            if (modelResult.isPresent()) {
                return modelResult.get();
            }
        }
        return ruleAnalysis(event);
    }

    private Optional<AnalysisResult> modelAnalysis(EventEntity event) {
        String systemPrompt = """
                你是 do not miss 的 Event Quality Agent，负责在活动发布审核前做信息质量预处理。
                你只能根据输入活动本身判断，不要编造不存在的报名方式、组织背景或活动结果。
                请输出严格 JSON 对象，不要输出 Markdown。
                字段要求：
                {
                  "qualityScore": 0-100,
                  "reviewSuggestion": "APPROVE/NEEDS_REVISION/REJECT",
                  "difficulty": "zero/basic/intermediate/advanced/unknown",
                  "summary": "80字以内活动摘要",
                  "targetStudents": ["适合人群"],
                  "prerequisites": ["前置要求，没有则空数组"],
                  "learningOutcomes": ["可获得能力或经验"],
                  "extractedTags": ["检索和推荐标签"],
                  "abilityImpacts": [{"tag":"能力标签","score":1-30,"confidence":0.0-1.0,"evidence":"来自活动文本的证据"}],
                  "riskFlags": ["风险点，没有则空数组"],
                  "missingFields": ["缺失信息，没有则空数组"],
                  "confidence": 0.0-1.0
                }
                评分倾向：
                - 时间地点、内容、收益、适合人群、前置要求越清楚，分数越高。
                - 描述空泛、收益夸大、关键信息缺失、疑似标题党时降低分数。
                - 不是所有活动都必须有前置要求；公益或低门槛活动可以为空。
                - abilityImpacts 用于学生完成活动后的成长标签加分，必须保守、有证据。
                """;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getId());
        payload.put("title", event.getTitle());
        payload.put("organizationName", event.getOrganizationName());
        payload.put("category", event.getCategory().label());
        payload.put("startTime", event.getStartTime());
        payload.put("endTime", event.getEndTime());
        payload.put("location", event.getLocation());
        payload.put("content", event.getContent());
        payload.put("benefitType", event.getBenefitType().label());
        payload.put("skill", event.getSkill());
        payload.put("moneyAmount", event.getMoneyAmount());

        return llmClient.chatForJson(systemPrompt, toJson(payload), EventQualityDtos.QualityModelResponse.class)
                .map(response -> fromModelResponse(response, event));
    }

    private AnalysisResult fromModelResponse(EventQualityDtos.QualityModelResponse response, EventEntity event) {
        List<EventQualityDtos.AbilityImpact> abilityImpacts = sanitizeAbilityImpacts(response.abilityImpacts(), event);
        return new AnalysisResult(
                clamp(response.qualityScore() == null ? 60 : response.qualityScore(), 0, 100),
                normalizeSuggestion(response.reviewSuggestion(), response.qualityScore()),
                normalizeDifficulty(response.difficulty()),
                compact(firstPresent(response.summary(), defaultSummary(event)), 800),
                sanitizeStrings(response.targetStudents()),
                sanitizeStrings(response.prerequisites()),
                sanitizeStrings(response.learningOutcomes()),
                sanitizeStrings(response.extractedTags()),
                abilityImpacts,
                sanitizeStrings(response.riskFlags()),
                sanitizeStrings(response.missingFields()),
                llmClient.modeLabel(),
                clamp(response.confidence() == null ? 0.65 : response.confidence(), 0.0, 1.0)
        );
    }

    private AnalysisResult ruleAnalysis(EventEntity event) {
        List<String> missingFields = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();
        List<String> targetStudents = new ArrayList<>();
        List<String> prerequisites = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        List<EventQualityDtos.AbilityImpact> impacts = new ArrayList<>();

        int score = 62;
        if (event.getContent() == null || event.getContent().trim().length() < 18) {
            missingFields.add("活动内容描述过短");
            score -= 18;
        }
        if (event.getLocation() == null || event.getLocation().isBlank()) {
            missingFields.add("活动地点缺失");
            score -= 12;
        }
        if (event.getSkill() == null || event.getSkill().isBlank()) {
            if (event.getBenefitType().hasSkill()) {
                missingFields.add("技能收益描述缺失");
                score -= 15;
            } else {
                riskFlags.add("活动没有明确技能收益，推荐时需要结合报酬与内容保守判断");
                score -= 6;
            }
        }
        if (event.getBenefitType().hasMoney() && event.getMoneyAmount() == null) {
            missingFields.add("金钱报酬金额缺失");
            score -= 12;
        }

        String text = textOf(event);
        String difficulty = "unknown";
        if (containsAny(text, "零基础", "从零", "初学", "新手", "入门")) {
            difficulty = "zero";
            targetStudents.add("零基础或初学者");
        } else if (containsAny(text, "初级", "基础", "简单")) {
            difficulty = "basic";
            targetStudents.add("有基础兴趣的学生");
        } else if (containsAny(text, "竞赛", "比赛", "项目", "开发", "研究", "论文")) {
            difficulty = "intermediate";
            prerequisites.add("需要相关方向的基本理解");
        }

        addTagIfMatched(text, tags, impacts, "Java 项目开发", "Java 项目开发", "活动文本包含 Java 或项目开发相关内容", 22,
                "java", "spring", "后端", "程序设计");
        addTagIfMatched(text, tags, impacts, "AI Agent 学习", "AI Agent 学习", "活动文本包含 AI/Agent/LLM 相关内容", 22,
                "ai", "agent", "llm", "rag", "大模型", "人工智能");
        addTagIfMatched(text, tags, impacts, "日语沟通", "日语沟通", "活动文本包含日语沟通或留学相关内容", 18,
                "日语", "日本语", "n5", "n4", "n3", "口语", "留学");
        addTagIfMatched(text, tags, impacts, "调研分析", "调研分析", "活动文本包含问卷、访谈或研究相关内容", 17,
                "调研", "问卷", "访谈", "研究", "数据");
        addTagIfMatched(text, tags, impacts, "活动运营", "活动运营", "活动文本包含现场执行或运营相关内容", 16,
                "运营", "现场", "引导", "签到", "执行", "组织");
        addTagIfMatched(text, tags, impacts, "内容创作", "内容创作", "活动文本包含文案、写作或内容运营相关内容", 15,
                "文案", "写作", "内容", "推文", "社媒");

        if (outcomes.isEmpty() && event.getSkill() != null && !event.getSkill().isBlank()) {
            outcomes.addAll(splitSkill(event.getSkill()));
        }
        if (outcomes.isEmpty()) {
            outcomes.add(event.getCategory().label() + "实践经验");
        }
        for (String outcome : outcomes) {
            if (!tags.contains(outcome)) {
                tags.add(outcome);
            }
        }
        if (targetStudents.isEmpty()) {
            targetStudents.add("对" + event.getCategory().label() + "方向感兴趣的学生");
        }
        if (impacts.isEmpty()) {
            impacts.add(new EventQualityDtos.AbilityImpact(
                    event.getCategory().label() + "实践",
                    10,
                    0.55,
                    "根据活动类别和内容保守推断"
            ));
        }

        score = clamp(score + Math.min(tags.size() * 3, 12), 0, 100);
        String suggestion = score >= 70 ? "APPROVE" : score >= 45 ? "NEEDS_REVISION" : "REJECT";
        return new AnalysisResult(
                score,
                suggestion,
                difficulty,
                defaultSummary(event),
                targetStudents,
                prerequisites,
                outcomes,
                tags,
                impacts,
                riskFlags,
                missingFields,
                "rule",
                0.58
        );
    }

    private EventQualityReportEntity upsertReport(EventEntity event, AnalysisResult analysis) {
        EventQualityReportEntity report = reportRepository.findByEventId(event.getId())
                .orElseGet(EventQualityReportEntity::new);
        report.setEventId(event.getId());
        report.setQualityScore(analysis.qualityScore());
        report.setQualityLevel(qualityLevel(analysis.qualityScore()));
        report.setReviewSuggestion(analysis.reviewSuggestion());
        report.setDifficulty(analysis.difficulty());
        report.setSummary(analysis.summary());
        report.setTargetStudentsJson(toJson(analysis.targetStudents()));
        report.setPrerequisitesJson(toJson(analysis.prerequisites()));
        report.setLearningOutcomesJson(toJson(analysis.learningOutcomes()));
        report.setExtractedTagsJson(toJson(analysis.extractedTags()));
        report.setAbilityImpactsJson(toJson(analysis.abilityImpacts()));
        report.setRiskFlagsJson(toJson(analysis.riskFlags()));
        report.setMissingFieldsJson(toJson(analysis.missingFields()));
        report.setDuplicateEventIdsJson(toJson(detectDuplicateEventIds(event)));
        report.setModelName(analysis.modelName());
        report.setConfidence(analysis.confidence());
        return reportRepository.save(report);
    }

    private EventReviewStatus applyReviewStatus(EventEntity event, EventQualityReportEntity report) {
        EventReviewStatus status = switch (report.getReviewSuggestion()) {
            case "APPROVE" -> EventReviewStatus.APPROVED;
            case "REJECT" -> EventReviewStatus.REJECTED;
            default -> EventReviewStatus.NEEDS_REVISION;
        };
        event.setReviewStatus(status);
        eventRepository.save(event);
        return status;
    }

    private List<Long> detectDuplicateEventIds(EventEntity event) {
        String title = normalize(event.getTitle());
        if (title.length() < 3) {
            return List.of();
        }
        return eventRepository.findByExpiredFalseAndReviewStatus(EventReviewStatus.APPROVED).stream()
                .filter(candidate -> !candidate.getId().equals(event.getId()))
                .filter(candidate -> similarity(title, normalize(candidate.getTitle())) >= 0.72
                        || sharedMeaningfulTerms(textOf(event), textOf(candidate)) >= 3)
                .map(EventEntity::getId)
                .limit(5)
                .toList();
    }

    private int sharedMeaningfulTerms(String left, String right) {
        LinkedHashSet<String> leftTerms = terms(left);
        LinkedHashSet<String> rightTerms = terms(right);
        leftTerms.retainAll(rightTerms);
        return leftTerms.size();
    }

    private LinkedHashSet<String> terms(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = normalize(value);
        for (String part : normalized.split("[\\s,，。；;:：!！?？()（）\\[\\]【】]+")) {
            if (part.length() >= 2 && !List.of("活动", "项目", "学习", "相关", "经验", "能力").contains(part)) {
                terms.add(part);
            }
        }
        return terms;
    }

    private double similarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        LinkedHashSet<Integer> leftChars = new LinkedHashSet<>();
        left.codePoints().forEach(leftChars::add);
        LinkedHashSet<Integer> rightChars = new LinkedHashSet<>();
        right.codePoints().forEach(rightChars::add);
        LinkedHashSet<Integer> intersection = new LinkedHashSet<>(leftChars);
        intersection.retainAll(rightChars);
        LinkedHashSet<Integer> union = new LinkedHashSet<>(leftChars);
        union.addAll(rightChars);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private List<EventQualityDtos.AbilityImpact> sanitizeAbilityImpacts(List<EventQualityDtos.AbilityImpact> impacts,
                                                                        EventEntity event) {
        if (impacts == null || impacts.isEmpty()) {
            return List.of(new EventQualityDtos.AbilityImpact(
                    event.getCategory().label() + "实践",
                    10,
                    0.5,
                    "模型未返回能力影响，使用活动类别兜底"
            ));
        }
        Map<String, EventQualityDtos.AbilityImpact> byTag = new LinkedHashMap<>();
        for (EventQualityDtos.AbilityImpact impact : impacts) {
            if (impact == null || impact.tag() == null || impact.tag().isBlank()) {
                continue;
            }
            String tag = compact(impact.tag().trim(), 80);
            int score = clamp(impact.score() == null ? 8 : impact.score(), 1, 30);
            double confidence = clamp(impact.confidence() == null ? 0.55 : impact.confidence(), 0.0, 1.0);
            String evidence = compact(firstPresent(impact.evidence(), "活动结构化预处理结果"), 300);
            byTag.putIfAbsent(tag, new EventQualityDtos.AbilityImpact(tag, score, confidence, evidence));
            if (byTag.size() >= 5) {
                break;
            }
        }
        return byTag.isEmpty()
                ? List.of(new EventQualityDtos.AbilityImpact(event.getCategory().label() + "实践", 10, 0.5, "活动类别兜底"))
                : List.copyOf(byTag.values());
    }

    private List<String> sanitizeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(compact(value.trim(), 80));
            }
            if (cleaned.size() >= MAX_LIST_SIZE) {
                break;
            }
        }
        return List.copyOf(cleaned);
    }

    private String normalizeSuggestion(String suggestion, Integer score) {
        String normalized = suggestion == null ? "" : suggestion.trim().toUpperCase(Locale.ROOT);
        if (List.of("APPROVE", "NEEDS_REVISION", "REJECT").contains(normalized)) {
            return normalized;
        }
        int actualScore = score == null ? 60 : score;
        return actualScore >= 70 ? "APPROVE" : actualScore >= 45 ? "NEEDS_REVISION" : "REJECT";
    }

    private String normalizeDifficulty(String difficulty) {
        String normalized = difficulty == null ? "" : difficulty.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "zero", "basic", "intermediate", "advanced" -> normalized;
            default -> "unknown";
        };
    }

    private String qualityLevel(int score) {
        if (score >= 80) {
            return "HIGH";
        }
        if (score >= 55) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private void addTagIfMatched(String text,
                                 List<String> tags,
                                 List<EventQualityDtos.AbilityImpact> impacts,
                                 String tag,
                                 String ability,
                                 String evidence,
                                 int score,
                                 String... keywords) {
        if (containsAny(text, keywords)) {
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
            impacts.add(new EventQualityDtos.AbilityImpact(ability, score, 0.7, evidence));
        }
    }

    private List<String> splitSkill(String skill) {
        if (skill == null || skill.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : skill.split("[,，、;；\\s]+")) {
            if (!part.isBlank()) {
                values.add(compact(part.trim(), 80));
            }
            if (values.size() >= 5) {
                break;
            }
        }
        return values;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String textOf(EventEntity event) {
        return String.join(" ",
                nullToEmpty(event.getTitle()),
                nullToEmpty(event.getOrganizationName()),
                event.getCategory() == null ? "" : event.getCategory().label(),
                nullToEmpty(event.getLocation()),
                nullToEmpty(event.getContent()),
                event.getBenefitType() == null ? "" : event.getBenefitType().label(),
                nullToEmpty(event.getSkill())
        ).toLowerCase(Locale.ROOT);
    }

    private String defaultSummary(EventEntity event) {
        String content = compact(nullToEmpty(event.getContent()), 80);
        return event.getTitle() + "：" + content;
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (RuntimeException | JsonProcessingException ex) {
            return List.of();
        }
    }

    private List<Long> readLongList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (RuntimeException | JsonProcessingException ex) {
            return List.of();
        }
    }

    public List<EventQualityDtos.AbilityImpact> readAbilityImpacts(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (RuntimeException | JsonProcessingException ex) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String compact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String normalize(String value) {
        return nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }

    private record AnalysisResult(
            int qualityScore,
            String reviewSuggestion,
            String difficulty,
            String summary,
            List<String> targetStudents,
            List<String> prerequisites,
            List<String> learningOutcomes,
            List<String> extractedTags,
            List<EventQualityDtos.AbilityImpact> abilityImpacts,
            List<String> riskFlags,
            List<String> missingFields,
            String modelName,
            double confidence
    ) {
    }
}
