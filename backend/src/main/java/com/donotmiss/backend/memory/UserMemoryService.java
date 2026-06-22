package com.donotmiss.backend.memory;

import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.challenge.ChallengeRepository;
import com.donotmiss.backend.challenge.ChallengeStatus;
import com.donotmiss.backend.coach.CoachLogEntity;
import com.donotmiss.backend.coach.CoachLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UserMemoryService {
    private static final List<String> SKILL_KEYWORDS = List.of(
            "日语", "英语", "翻译", "沟通", "运营", "执行", "协作", "调研", "访谈", "问卷",
            "数据", "复盘", "写作", "文案", "记录", "项目", "Java", "Go", "健身",
            "AI", "Agent", "LLM", "RAG", "Spring", "后端"
    );

    private final AchievementService achievementService;
    private final ChallengeRepository challengeRepository;
    private final CoachLogRepository coachLogRepository;
    private final UserProfileSnapshotRepository snapshotRepository;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public UserMemoryService(AchievementService achievementService,
                             ChallengeRepository challengeRepository,
                             CoachLogRepository coachLogRepository,
                             UserProfileSnapshotRepository snapshotRepository,
                             OpenAiCompatibleLlmClient llmClient,
                             ObjectMapper objectMapper) {
        this.achievementService = achievementService;
        this.challengeRepository = challengeRepository;
        this.coachLogRepository = coachLogRepository;
        this.snapshotRepository = snapshotRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public UserMemoryDtos.Profile profile(String userId) {
        return snapshotRepository.findByUserId(userId)
                .filter(snapshot -> !snapshot.isDirty())
                .map(this::profileFromSnapshot)
                .orElseGet(() -> ruleProfile(userId));
    }

    @Transactional
    public int refreshDirtyProfiles(int limit) {
        int max = Math.max(1, limit);
        int refreshed = 0;
        for (UserProfileSnapshotEntity snapshot : snapshotRepository.findTop20ByDirtyTrueOrderByUpdatedAtAsc().stream().limit(max).toList()) {
            refreshSnapshot(snapshot.getUserId());
            refreshed += 1;
        }
        return refreshed;
    }

    @Transactional
    public UserMemoryDtos.Profile refreshSnapshot(String userId) {
        UserMemoryDtos.Profile ruleProfile = ruleProfile(userId);
        java.util.Optional<UserMemoryDtos.Profile> modelProfile = llmProfile(userId, ruleProfile);
        UserMemoryDtos.Profile finalProfile = modelProfile.orElse(ruleProfile);
        UserProfileSnapshotEntity snapshot = snapshotRepository.findByUserId(userId)
                .orElseGet(UserProfileSnapshotEntity::new);
        snapshot.setUserId(userId);
        writeProfileToSnapshot(snapshot, finalProfile, modelProfile.isPresent() ? llmClient.modeLabel() : "rule-fallback", null);
        snapshotRepository.save(snapshot);
        return finalProfile;
    }

    public String promptText(UserMemoryDtos.Profile profile) {
        return String.join("\n",
                "summary: " + profile.summary(),
                "preferredCategories: " + String.join(", ", profile.preferredCategories()),
                "preferredLocations: " + String.join(", ", profile.preferredLocations()),
                "benefitPreferences: " + String.join(", ", profile.benefitPreferences()),
                "evidenceKeywords: " + String.join(", ", profile.evidenceKeywords()),
                "completedCount: " + profile.completedCount(),
                "activeChallengeCount: " + profile.activeChallengeCount(),
                "coachLogCount: " + profile.coachLogCount()
        );
    }

    private java.util.Optional<UserMemoryDtos.Profile> llmProfile(String userId, UserMemoryDtos.Profile ruleProfile) {
        if (!llmClient.isEnabled()) {
            return java.util.Optional.empty();
        }

        List<AchievementRecordEntity> records = achievementService.historyEntities(userId).stream().limit(30).toList();
        List<CoachLogEntity> logs = coachLogRepository.findByUserIdOrderByLogDateDesc(userId).stream().limit(20).toList();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ruleProfile", ruleProfile);
        context.put("completedRecords", records.stream().map(this::recordContext).toList());
        context.put("coachLogs", logs.stream().map(this::logContext).toList());

        String systemPrompt = """
                你是 do not miss 的用户成长画像 Agent。
                你只能根据后端提供的真实活动、挑战和教练日志生成画像，不能编造经历、证书、奖项或组织。
                你的任务不是改变画像结构，而是为固定结构填入更自然、更有语义理解的内容。
                输出严格 JSON，不要输出 Markdown。
                JSON 格式：
                {
                  "summary":"用户成长画像摘要",
                  "strengths":["能力优势"],
                  "preferredCategories":["偏好类别"],
                  "preferredLocations":["偏好地点"],
                  "benefitPreferences":["技能/经验 或 金钱报酬 等"],
                  "evidenceKeywords":["关键词"],
                  "recentSignals":[{"type":"EVENT","title":"标题","category":"类别","detail":"证据摘要","occurredAt":"2026-06-01T00:00:00Z"}]
                }
                """;
        String userPrompt = """
                请根据下面 JSON 生成用户画像。
                要求：
                1. summary 控制在 180 字以内，概括用户近期目标、已有经验和推荐时应注意的倾向。
                2. strengths 返回 3-6 个短句，必须能从真实记录中找到依据。
                3. preferredCategories、preferredLocations、benefitPreferences、evidenceKeywords 都返回短列表。
                4. recentSignals 最多返回 8 条，优先选择能解释画像的近期记录。
                5. 如果真实数据不足，请明确说明“记录较少”，不要过度推断。

                后端数据 JSON：
                %s
                """.formatted(toJson(context));

        return llmClient.chatForJson(systemPrompt, userPrompt, UserMemoryDtos.ProfileModelResponse.class)
                .map(output -> normalizeModelProfile(output, ruleProfile));
    }

    private UserMemoryDtos.Profile normalizeModelProfile(UserMemoryDtos.ProfileModelResponse output,
                                                        UserMemoryDtos.Profile fallback) {
        return new UserMemoryDtos.Profile(
                compact(firstPresent(output.summary(), fallback.summary()), 600),
                cleanList(output.strengths(), fallback.strengths(), 6, 80),
                cleanList(output.preferredCategories(), fallback.preferredCategories(), 4, 40),
                cleanList(output.preferredLocations(), fallback.preferredLocations(), 4, 40),
                cleanList(output.benefitPreferences(), fallback.benefitPreferences(), 4, 40),
                cleanList(output.evidenceKeywords(), fallback.evidenceKeywords(), 10, 40),
                cleanSignals(output.recentSignals(), fallback.recentSignals()),
                fallback.completedCount(),
                fallback.activeChallengeCount(),
                fallback.coachLogCount()
        );
    }

    private UserMemoryDtos.Profile ruleProfile(String userId) {
        List<AchievementRecordEntity> records = achievementService.historyEntities(userId);
        long activeChallengeCount = challengeRepository.countByUserIdAndStatus(userId, ChallengeStatus.ACTIVE);
        List<CoachLogEntity> logs = coachLogRepository.findByUserIdOrderByLogDateDesc(userId);

        List<String> categories = topKeys(records, AchievementRecordEntity::getCategory, 4);
        List<String> locations = topKeys(records, AchievementRecordEntity::getLocation, 4);
        List<String> keywords = extractKeywords(records, logs);
        List<String> benefits = benefitPreferences(records);
        List<String> strengths = buildStrengths(categories, keywords, records.size(), activeChallengeCount);
        List<UserMemoryDtos.MemorySignal> signals = mergeSignals(records, logs).stream()
                .limit(8)
                .toList();

        return new UserMemoryDtos.Profile(
                buildSummary(records.size(), activeChallengeCount, logs.size(), categories, keywords),
                strengths,
                categories,
                locations,
                benefits,
                keywords,
                signals,
                records.size(),
                activeChallengeCount,
                logs.size()
        );
    }

    private UserMemoryDtos.Profile profileFromSnapshot(UserProfileSnapshotEntity snapshot) {
        return new UserMemoryDtos.Profile(
                snapshot.getSummary(),
                readStringList(snapshot.getStrengthsJson()),
                readStringList(snapshot.getPreferredCategoriesJson()),
                readStringList(snapshot.getPreferredLocationsJson()),
                readStringList(snapshot.getBenefitPreferencesJson()),
                readStringList(snapshot.getEvidenceKeywordsJson()),
                readSignals(snapshot.getRecentSignalsJson()),
                snapshot.getCompletedCount(),
                snapshot.getActiveChallengeCount(),
                snapshot.getCoachLogCount()
        );
    }

    private void writeProfileToSnapshot(UserProfileSnapshotEntity snapshot,
                                        UserMemoryDtos.Profile profile,
                                        String generatedBy,
                                        String errorMessage) {
        snapshot.setSummary(compact(profile.summary(), 1200));
        snapshot.setStrengthsJson(toJson(profile.strengths()));
        snapshot.setPreferredCategoriesJson(toJson(profile.preferredCategories()));
        snapshot.setPreferredLocationsJson(toJson(profile.preferredLocations()));
        snapshot.setBenefitPreferencesJson(toJson(profile.benefitPreferences()));
        snapshot.setEvidenceKeywordsJson(toJson(profile.evidenceKeywords()));
        snapshot.setRecentSignalsJson(toJson(profile.recentSignals()));
        snapshot.setCompletedCount(profile.completedCount());
        snapshot.setActiveChallengeCount(profile.activeChallengeCount());
        snapshot.setCoachLogCount(profile.coachLogCount());
        snapshot.setDirty(false);
        snapshot.setGeneratedBy(compact(generatedBy, 80));
        snapshot.setErrorMessage(errorMessage == null ? null : compact(errorMessage, 1000));
    }

    private List<UserMemoryDtos.MemorySignal> mergeSignals(List<AchievementRecordEntity> records, List<CoachLogEntity> logs) {
        List<UserMemoryDtos.MemorySignal> signals = new ArrayList<>();
        records.stream().map(this::toSignal).forEach(signals::add);
        logs.stream()
                .map(log -> new UserMemoryDtos.MemorySignal(
                        "COACH_LOG",
                        log.getTitle(),
                        "日志",
                        compact(firstPresent(log.getSummary(), log.getContent()), 180),
                        log.getCreatedAt()
                ))
                .forEach(signals::add);
        signals.sort(Comparator.comparing(UserMemoryDtos.MemorySignal::occurredAt).reversed());
        return signals;
    }

    private UserMemoryDtos.MemorySignal toSignal(AchievementRecordEntity record) {
        String detail = firstPresent(record.getDid(), record.getLearned(), record.getSkill(), record.getContent());
        return new UserMemoryDtos.MemorySignal(
                record.getSourceType().name(),
                record.getEventTitle(),
                record.getCategory(),
                compact(detail, 180),
                record.getCompletedAt()
        );
    }

    private String buildSummary(long completedCount, long activeChallengeCount, long coachLogCount, List<String> categories, List<String> keywords) {
        if (completedCount == 0 && activeChallengeCount == 0 && coachLogCount == 0) {
            return "学生还没有形成稳定的长期记录，推荐时应优先选择低门槛、容易开始的活动或挑战。";
        }

        String categoryText = categories.isEmpty() ? "多元实践" : String.join("、", categories);
        String keywordText = keywords.isEmpty() ? "主动参与" : String.join("、", keywords.stream().limit(5).toList());
        return "学生已完成 " + completedCount + " 条实践/挑战记录，仍有 " + activeChallengeCount
                + " 个进行中挑战，并沉淀了 " + coachLogCount + " 篇教练日志。当前经历集中在 " + categoryText
                + "，可观察到的能力关键词包括 " + keywordText + "。";
    }

    private List<String> buildStrengths(List<String> categories, List<String> keywords, int completedCount, long activeChallengeCount) {
        List<String> strengths = new ArrayList<>();
        if (completedCount > 0) {
            strengths.add("有真实完成记录");
        }
        if (activeChallengeCount > 0) {
            strengths.add("正在推进个人目标");
        }
        strengths.addAll(keywords.stream().limit(4).map(keyword -> keyword + "相关经验").toList());
        if (strengths.isEmpty() && !categories.isEmpty()) {
            strengths.add(categories.getFirst() + "方向探索");
        }
        return strengths.isEmpty() ? List.of("待积累实践数据") : strengths.stream().distinct().limit(6).toList();
    }

    private List<String> benefitPreferences(List<AchievementRecordEntity> records) {
        long paidCount = records.stream()
                .filter(record -> record.getMoneyAmount() != null && record.getMoneyAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();
        long skillCount = records.stream()
                .filter(record -> record.getSkill() != null && !record.getSkill().isBlank())
                .count();

        List<String> preferences = new ArrayList<>();
        if (skillCount > 0) {
            preferences.add("技能/经验");
        }
        if (paidCount > 0) {
            preferences.add("金钱报酬");
        }
        return preferences.isEmpty() ? List.of("暂无明显偏好") : preferences;
    }

    private List<String> extractKeywords(List<AchievementRecordEntity> records, List<CoachLogEntity> logs) {
        String text = records.stream()
                .map(record -> String.join(" ",
                        record.getEventTitle(),
                        record.getOrganizationName(),
                        record.getCategory(),
                        nullToEmpty(record.getLocation()),
                        nullToEmpty(record.getContent()),
                        nullToEmpty(record.getSkill()),
                        nullToEmpty(record.getDid()),
                        nullToEmpty(record.getLearned())))
                .reduce("", (left, right) -> left + " " + right)
                .toLowerCase(Locale.ROOT)
                + " "
                + logs.stream()
                .map(log -> String.join(" ", log.getTitle(), log.getSummary(), log.getContent(), nullToEmpty(log.getTags())))
                .reduce("", (left, right) -> left + " " + right)
                .toLowerCase(Locale.ROOT);

        return SKILL_KEYWORDS.stream()
                .filter(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT)))
                .limit(10)
                .toList();
    }

    private List<String> topKeys(List<AchievementRecordEntity> records,
                                 java.util.function.Function<AchievementRecordEntity, String> extractor,
                                 int limit) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AchievementRecordEntity record : records) {
            String value = extractor.apply(record);
            if (value != null && !value.isBlank()) {
                counts.merge(value.trim(), 1L, Long::sum);
            }
        }

        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, Object> recordContext(AchievementRecordEntity record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceType", record.getSourceType().name());
        data.put("title", record.getEventTitle());
        data.put("organizationName", record.getOrganizationName());
        data.put("category", record.getCategory());
        data.put("location", record.getLocation());
        data.put("content", compact(record.getContent(), 260));
        data.put("skill", record.getSkill());
        data.put("moneyAmount", record.getMoneyAmount());
        data.put("did", compact(record.getDid(), 220));
        data.put("learned", compact(record.getLearned(), 220));
        data.put("completedAt", record.getCompletedAt());
        return data;
    }

    private Map<String, Object> logContext(CoachLogEntity log) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", log.getTitle());
        data.put("summary", log.getSummary());
        data.put("content", compact(log.getContent(), 320));
        data.put("tags", log.getTags());
        data.put("logDate", log.getLogDate());
        data.put("createdAt", log.getCreatedAt());
        return data;
    }

    private List<String> cleanList(List<String> values, List<String> fallback, int limit, int maxLength) {
        List<String> source = values == null || values.isEmpty() ? fallback : values;
        if (source == null) {
            return List.of();
        }
        return source.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> compact(value.trim(), maxLength))
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<UserMemoryDtos.MemorySignal> cleanSignals(List<UserMemoryDtos.MemorySignalModel> values,
                                                           List<UserMemoryDtos.MemorySignal> fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        List<UserMemoryDtos.MemorySignal> signals = values.stream()
                .filter(value -> value != null && value.title() != null && !value.title().isBlank())
                .map(value -> new UserMemoryDtos.MemorySignal(
                        compact(firstPresent(value.type(), "MEMORY"), 32),
                        compact(value.title(), 120),
                        compact(firstPresent(value.category(), "成长记录"), 60),
                        compact(firstPresent(value.detail(), ""), 180),
                        parseInstant(value.occurredAt())
                ))
                .limit(8)
                .toList();
        return signals.isEmpty() ? fallback : signals;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(nullToEmptyJsonArray(json), new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private List<UserMemoryDtos.MemorySignal> readSignals(String json) {
        try {
            return objectMapper.readValue(nullToEmptyJsonArray(json), new TypeReference<List<UserMemoryDtos.MemorySignal>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String nullToEmptyJsonArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String compact(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
