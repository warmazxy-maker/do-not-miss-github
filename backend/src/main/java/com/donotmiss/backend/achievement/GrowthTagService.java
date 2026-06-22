package com.donotmiss.backend.achievement;

import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class GrowthTagService {
    private final AchievementRecordRepository achievementRecordRepository;
    private final GrowthTagRepository tagRepository;
    private final GrowthTagEvidenceRepository evidenceRepository;
    private final OpenAiCompatibleLlmClient llmClient;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public GrowthTagService(AchievementRecordRepository achievementRecordRepository,
                            GrowthTagRepository tagRepository,
                            GrowthTagEvidenceRepository evidenceRepository,
                            OpenAiCompatibleLlmClient llmClient,
                            AgentRunService agentRunService,
                            ObjectMapper objectMapper) {
        this.achievementRecordRepository = achievementRecordRepository;
        this.tagRepository = tagRepository;
        this.evidenceRepository = evidenceRepository;
        this.llmClient = llmClient;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AchievementDtos.GrowthTagResponse> tags(String userId) {
        return tagRepository.findByUserIdOrderByScoreDescLastUpdatedAtDesc(userId).stream()
                .map(AchievementDtos.GrowthTagResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AchievementDtos.GrowthTagDetailResponse tagDetail(String userId, Long tagId) {
        GrowthTagEntity tag = tagRepository.findByIdAndUserId(tagId, userId)
                .orElseThrow(() -> ApiException.notFound("成长标签不存在：" + tagId));
        List<AchievementDtos.GrowthTagEvidenceResponse> evidences = evidenceRepository.findByTag_IdAndUserIdOrderByOccurredAtAsc(tagId, userId)
                .stream()
                .map(AchievementDtos.GrowthTagEvidenceResponse::from)
                .toList();
        return new AchievementDtos.GrowthTagDetailResponse(AchievementDtos.GrowthTagResponse.from(tag), evidences);
    }

    @Transactional
    public AchievementDtos.GrowthTagRebuildResponse rebuild(String userId) {
        evidenceRepository.deleteByUserId(userId);
        tagRepository.deleteByUserId(userId);

        List<AchievementRecordEntity> records = achievementRecordRepository.findByUserIdOrderByCompletedAtDesc(userId);
        for (AchievementRecordEntity record : records) {
            upsertFromRecord(record);
        }
        int tagCount = tagRepository.findByUserIdOrderByScoreDescLastUpdatedAtDesc(userId).size();
        int evidenceCount = evidenceRepository.findByUserIdOrderByOccurredAtDesc(userId).size();
        return new AchievementDtos.GrowthTagRebuildResponse(records.size(), tagCount, evidenceCount);
    }

    @Transactional
    public boolean upsertFromRecordId(Long recordId, String userId) {
        if (recordId == null) {
            return false;
        }
        Optional<AchievementRecordEntity> record = userId == null || userId.isBlank()
                ? achievementRecordRepository.findById(recordId)
                : achievementRecordRepository.findByIdAndUserId(recordId, userId);
        record.ifPresent(this::upsertFromRecord);
        return record.isPresent();
    }

    @Transactional
    public void upsertFromRecord(AchievementRecordEntity record) {
        if (record == null || record.getId() == null) {
            return;
        }

        Long runId = agentRunService.startRun(
                record.getUserId(),
                AgentRunType.GROWTH_TAG_EXTRACTION,
                record.getEventTitle(),
                "recordId=" + record.getId() + ", sourceType=" + record.getSourceType()
        );
        try {
            List<TagCandidate> candidates = selectCandidates(record, runId);
            Map<String, TagCandidate> byNormalized = new LinkedHashMap<>();
            for (TagCandidate candidate : candidates) {
                byNormalized.putIfAbsent(candidate.normalizedName(), candidate);
            }

            Set<Long> affectedTagIds = new LinkedHashSet<>();
            for (GrowthTagEvidenceEntity oldEvidence : evidenceRepository.findByRecord_IdAndUserId(record.getId(), record.getUserId())) {
                affectedTagIds.add(oldEvidence.getTag().getId());
                if (!byNormalized.containsKey(oldEvidence.getTag().getNormalizedName())) {
                    evidenceRepository.delete(oldEvidence);
                }
            }

            for (TagCandidate candidate : byNormalized.values()) {
                GrowthTagEntity tag = tagRepository.findByUserIdAndNormalizedName(record.getUserId(), candidate.normalizedName())
                        .orElseGet(() -> createTag(record.getUserId(), candidate));
                GrowthTagEvidenceEntity evidence = evidenceRepository.findByTag_IdAndRecord_Id(tag.getId(), record.getId())
                        .orElseGet(GrowthTagEvidenceEntity::new);
                evidence.setUserId(record.getUserId());
                evidence.setTag(tag);
                evidence.setRecord(record);
                evidence.setSourceType(record.getSourceType());
                evidence.setSourceId(record.getSourceId());
                evidence.setTitle(compact(record.getEventTitle(), 160));
                evidence.setSummary(compact(candidate.evidenceSummary(), 800));
                evidence.setDid(compact(record.getDid(), 1200));
                evidence.setLearned(compact(record.getLearned(), 1200));
                evidence.setScoreDelta(candidate.scoreDelta());
                if (candidate.milestone()) {
                    evidence.setMilestone(true);
                    evidence.setMilestoneReason(compact(candidate.milestoneReason(), 500));
                }
                if (evidence.getOccurredAt() == null) {
                    evidence.setOccurredAt(record.getCompletedAt());
                }
                evidenceRepository.save(evidence);
                affectedTagIds.add(tag.getId());
            }

            for (Long tagId : affectedTagIds) {
                tagRepository.findById(tagId).ifPresent(this::refreshAggregate);
            }
            agentRunService.finishRun(runId, "growthTags=" + byNormalized.keySet());
        } catch (RuntimeException ex) {
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    @Transactional
    public AchievementDtos.GrowthTagEvidenceResponse markMilestone(String userId,
                                                                   Long evidenceId,
                                                                   AchievementDtos.MilestoneRequest request) {
        GrowthTagEvidenceEntity evidence = evidenceRepository.findByIdAndUserId(evidenceId, userId)
                .orElseThrow(() -> ApiException.notFound("成长证据不存在：" + evidenceId));
        evidence.setMilestone(request.milestone());
        evidence.setMilestoneReason(request.milestone() ? compact(request.reason(), 500) : null);
        GrowthTagEvidenceEntity saved = evidenceRepository.save(evidence);
        refreshAggregate(saved.getTag());
        return AchievementDtos.GrowthTagEvidenceResponse.from(saved);
    }

    private GrowthTagEntity createTag(String userId, TagCandidate candidate) {
        GrowthTagEntity tag = new GrowthTagEntity();
        tag.setUserId(userId);
        tag.setName(candidate.name());
        tag.setNormalizedName(candidate.normalizedName());
        tag.setDescription(candidate.description());
        tag.setScore(0);
        tag.setEvidenceCount(0);
        tag.setImportanceScore(0);
        tag.setLastUpdatedAt(Instant.now());
        return tagRepository.save(tag);
    }

    private void refreshAggregate(GrowthTagEntity tag) {
        List<GrowthTagEvidenceEntity> evidences = evidenceRepository.findByTag_IdAndUserIdOrderByOccurredAtAsc(tag.getId(), tag.getUserId());
        if (evidences.isEmpty()) {
            tagRepository.delete(tag);
            return;
        }

        int score = evidences.stream().mapToInt(GrowthTagEvidenceEntity::getScoreDelta).sum();
        int importance = evidences.stream()
                .filter(GrowthTagEvidenceEntity::isMilestone)
                .mapToInt(evidence -> 10 + Math.max(evidence.getScoreDelta(), 0))
                .sum();
        Instant lastUpdated = evidences.stream()
                .map(GrowthTagEvidenceEntity::getOccurredAt)
                .filter(time -> time != null)
                .max(Comparator.naturalOrder())
                .orElse(Instant.now());

        tag.setScore(Math.min(score, 999));
        tag.setEvidenceCount(evidences.size());
        tag.setImportanceScore(Math.min(importance, 999));
        tag.setLastUpdatedAt(lastUpdated);
        tagRepository.save(tag);
    }

    private List<TagCandidate> selectCandidates(AchievementRecordEntity record, Long runId) {
        if (llmClient.isEnabled()) {
            Long llmStepId = agentRunService.startStep(
                    runId,
                    AgentStepName.GROWTH_TAG_EXTRACTION,
                    "LLM extracts growth tags for recordId=" + record.getId()
            );
            try {
                List<TagCandidate> llmCandidates = llmCandidates(record);
                if (!llmCandidates.isEmpty()) {
                    agentRunService.completeStep(llmStepId, "mode=" + llmClient.modeLabel() + ", tags=" + tagNames(llmCandidates));
                    Long validationStepId = agentRunService.startStep(
                            runId,
                            AgentStepName.GROWTH_TAG_VALIDATION,
                            "dedupe and clamp model tag candidates"
                    );
                    agentRunService.completeStep(validationStepId, "accepted=" + llmCandidates.size());
                    return llmCandidates;
                }
                agentRunService.completeStep(llmStepId, "mode=" + llmClient.modeLabel() + ", no valid tag returned");
            } catch (RuntimeException ex) {
                agentRunService.failStep(llmStepId, ex);
            }
        }

        Long fallbackStepId = agentRunService.startStep(
                runId,
                AgentStepName.RULE_FALLBACK,
                "use local keyword rules for growth tags"
        );
        List<TagCandidate> fallback = ruleCandidates(record);
        agentRunService.completeStep(fallbackStepId, "tags=" + tagNames(fallback));
        return fallback;
    }

    private List<TagCandidate> llmCandidates(AchievementRecordEntity record) {
        String systemPrompt = """
                你是 Do Not Miss 的个人成就标签抽取 Agent。
                你的任务是把一次已完成的活动/挑战经历，抽取成 1 到 3 个学生成长能力标签。
                规则：
                1. 只根据输入记录判断，不要编造活动。
                2. 如果 existingTags 里已有语义相同的标签，优先复用它的 normalizedName 和 name。
                3. 标签应是能力或成长方向，例如：AI Agent 学习、Java 后端开发、日语沟通、调研分析、团队协作。
                4. scoreDelta 表示这条经历给该标签增加的分数，范围 3 到 10，普通经历 4-6，重要成果 7-10。
                5. milestone 只在证书、考试通过、比赛获奖、重要项目上线、长期挑战完成等明显里程碑时为 true。
                6. 必须返回 JSON 对象，格式为 {"tags":[{"name":"...","normalizedName":"...","description":"...","scoreDelta":5,"evidenceSummary":"...","milestone":false,"milestoneReason":""}]}。
                """;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("record", recordContext(record));
        payload.put("existingTags", existingTagContext(record.getUserId()));

        Optional<AchievementDtos.GrowthTagModelResponse> response = llmClient.chatForJson(
                systemPrompt,
                toJson(payload),
                AchievementDtos.GrowthTagModelResponse.class
        );
        return response
                .map(AchievementDtos.GrowthTagModelResponse::tags)
                .map(items -> sanitizeModelCandidates(items, record))
                .orElse(List.of());
    }

    private Map<String, Object> recordContext(AchievementRecordEntity record) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("id", record.getId());
        context.put("sourceType", record.getSourceType() == null ? null : record.getSourceType().name());
        context.put("title", record.getEventTitle());
        context.put("organizationName", record.getOrganizationName());
        context.put("category", record.getCategory());
        context.put("location", record.getLocation());
        context.put("content", record.getContent());
        context.put("skill", record.getSkill());
        context.put("did", record.getDid());
        context.put("learned", record.getLearned());
        context.put("completedAt", record.getCompletedAt() == null ? null : record.getCompletedAt().toString());
        return context;
    }

    private List<Map<String, Object>> existingTagContext(String userId) {
        return tagRepository.findByUserIdOrderByScoreDescLastUpdatedAtDesc(userId).stream()
                .limit(20)
                .map(tag -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", tag.getName());
                    item.put("normalizedName", tag.getNormalizedName());
                    item.put("description", tag.getDescription());
                    item.put("score", tag.getScore());
                    item.put("evidenceCount", tag.getEvidenceCount());
                    return item;
                })
                .toList();
    }

    private List<TagCandidate> sanitizeModelCandidates(List<AchievementDtos.GrowthTagModelItem> items,
                                                       AchievementRecordEntity record) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        Map<String, TagCandidate> byNormalized = new LinkedHashMap<>();
        for (AchievementDtos.GrowthTagModelItem item : items) {
            TagCandidate candidate = fromModelItem(item, record);
            if (candidate != null) {
                byNormalized.putIfAbsent(candidate.normalizedName(), candidate);
            }
        }
        return byNormalized.values().stream().limit(3).toList();
    }

    private TagCandidate fromModelItem(AchievementDtos.GrowthTagModelItem item,
                                       AchievementRecordEntity record) {
        if (item == null) {
            return null;
        }

        String name = firstPresent(item.name(), item.normalizedName());
        if (name.isBlank()) {
            return null;
        }

        String normalizedName = normalize(firstPresent(item.normalizedName(), name));
        int scoreDelta = item.scoreDelta() == null ? defaultScoreDelta(record) : item.scoreDelta();
        scoreDelta = Math.min(Math.max(scoreDelta, 3), 10);
        String description = firstPresent(item.description(), name + "相关的成长经历");
        String evidenceSummary = firstPresent(item.evidenceSummary(), "完成经历：" + record.getEventTitle());
        boolean milestone = Boolean.TRUE.equals(item.milestone());
        String milestoneReason = milestone ? firstPresent(item.milestoneReason(), evidenceSummary) : null;

        return new TagCandidate(
                compact(name, 80),
                compact(normalizedName, 80),
                compact(description, 500),
                scoreDelta,
                compact(evidenceSummary, 800),
                milestone,
                compact(milestoneReason, 500)
        );
    }

    private List<TagCandidate> ruleCandidates(AchievementRecordEntity record) {
        String text = textOf(record);
        List<TagCandidate> candidates = new ArrayList<>();

        addIfMatched(candidates, text, "AI Agent 学习", "ai-agent", "围绕 AI、Agent、LLM 或 RAG 的学习与实践", record,
                "ai", "agent", "llm", "rag", "大模型", "人工智能", "智能体");
        addIfMatched(candidates, text, "Java 后端开发", "java-backend", "Java、Spring Boot、接口开发或后端项目实践", record,
                "java", "spring", "spring boot", "后端", "接口", "编程", "程序设计");
        addIfMatched(candidates, text, "日语沟通", "japanese-communication", "日语口语、阅读、翻译或中日沟通实践", record,
                "日语", "n5", "n4", "n3", "日本语", "口语", "留学");
        addIfMatched(candidates, text, "英语能力", "english-skill", "英语考试、英语沟通或英文内容理解", record,
                "英语", "cet", "六级", "四级", "雅思", "托福", "english");
        addIfMatched(candidates, text, "跨文化翻译", "cross-cultural-translation", "翻译、文化理解和跨文化表达相关经历", record,
                "翻译", "文化", "中日", "跨文化", "中文", "英文");
        addIfMatched(candidates, text, "调研分析", "research-analysis", "问卷、访谈、数据整理和研究分析实践", record,
                "研究", "调研", "访谈", "问卷", "数据", "分析", "复盘");
        addIfMatched(candidates, text, "活动运营", "event-operations", "活动现场、运营执行、组织协调相关实践", record,
                "运营", "活动", "现场", "执行", "组织", "协调", "会场");
        addIfMatched(candidates, text, "内容创作", "content-creation", "文案写作、内容整理、社媒运营或编辑实践", record,
                "内容", "文案", "写作", "编辑", "社媒", "推文", "记录");
        addIfMatched(candidates, text, "团队协作", "team-collaboration", "团队合作、沟通协调和共同完成任务", record,
                "团队", "协作", "合作", "沟通", "引导", "陪伴");
        addIfMatched(candidates, text, "健康管理", "health-management", "运动、健身、减重和身体管理相关挑战", record,
                "健身", "运动", "减肥", "跑步", "身体", "健康", "kg");

        if (candidates.isEmpty()) {
            if (record.getSourceType() == AchievementSourceType.CHALLENGE) {
                candidates.add(candidate("个人目标管理", "personal-goal", "自定义挑战、持续执行和目标复盘", record, "完成个人挑战：" + record.getEventTitle()));
            } else {
                String category = firstPresent(record.getCategory(), "综合");
                candidates.add(candidate(category + "实践", normalize(category) + "-practice", category + "方向的实践记录", record, "完成活动：" + record.getEventTitle()));
            }
        }

        return candidates.stream().limit(3).toList();
    }

    private void addIfMatched(List<TagCandidate> candidates,
                              String normalizedText,
                              String name,
                              String normalizedName,
                              String description,
                              AchievementRecordEntity record,
                              String... keywords) {
        for (String keyword : keywords) {
            if (normalizedText.contains(keyword.toLowerCase(Locale.ROOT))) {
                candidates.add(candidate(name, normalizedName, description, record, "命中关键词 `" + keyword + "`：" + record.getEventTitle()));
                return;
            }
        }
    }

    private TagCandidate candidate(String name,
                                   String normalizedName,
                                   String description,
                                   AchievementRecordEntity record,
                                   String evidenceSummary) {
        return new TagCandidate(
                name,
                compact(normalize(normalizedName), 80),
                compact(description, 500),
                defaultScoreDelta(record),
                compact(evidenceSummary, 800),
                false,
                null
        );
    }

    private int defaultScoreDelta(AchievementRecordEntity record) {
        int scoreDelta = 4;
        if (record.getSourceType() == AchievementSourceType.CHALLENGE) {
            scoreDelta += 1;
        }
        if (record.getDid() != null && !record.getDid().isBlank()) {
            scoreDelta += 1;
        }
        if (record.getLearned() != null && !record.getLearned().isBlank()) {
            scoreDelta += 1;
        }
        if (record.getSkill() != null && !record.getSkill().isBlank()) {
            scoreDelta += 1;
        }
        return Math.min(Math.max(scoreDelta, 3), 10);
    }

    private String textOf(AchievementRecordEntity record) {
        return String.join(" ",
                nullToEmpty(record.getEventTitle()),
                nullToEmpty(record.getOrganizationName()),
                nullToEmpty(record.getCategory()),
                nullToEmpty(record.getLocation()),
                nullToEmpty(record.getContent()),
                nullToEmpty(record.getSkill()),
                nullToEmpty(record.getDid()),
                nullToEmpty(record.getLearned())
        ).toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[\\s_]+", "-");
        normalized = normalized.replaceAll("[^a-z0-9\\-\\p{IsHan}]", "");
        return normalized.isBlank() ? "general-practice" : normalized;
    }

    private String compact(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String tagNames(List<TagCandidate> candidates) {
        return candidates == null ? "[]" : candidates.stream()
                .map(TagCandidate::normalizedName)
                .toList()
                .toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private record TagCandidate(
            String name,
            String normalizedName,
            String description,
            int scoreDelta,
            String evidenceSummary,
            boolean milestone,
            String milestoneReason
    ) {
    }
}
