package com.donotmiss.backend.achievement;

import com.donotmiss.backend.challenge.ChallengeEntity;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.common.PageResponse;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.memory.UserProfileSnapshotDirtyService;
import com.donotmiss.backend.mq.DomainEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AchievementService {
    private final AchievementRecordRepository achievementRecordRepository;
    private final UserProfileSnapshotDirtyService profileSnapshotDirtyService;
    private final DomainEventPublisher domainEventPublisher;

    public AchievementService(AchievementRecordRepository achievementRecordRepository,
                              UserProfileSnapshotDirtyService profileSnapshotDirtyService,
                              DomainEventPublisher domainEventPublisher) {
        this.achievementRecordRepository = achievementRecordRepository;
        this.profileSnapshotDirtyService = profileSnapshotDirtyService;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Transactional
    public AchievementRecordEntity createIfAbsent(String userId, EventEntity event) {
        return achievementRecordRepository.findByUserIdAndSourceTypeAndSourceId(userId, AchievementSourceType.EVENT, event.getId())
                .orElseGet(() -> {
                    AchievementRecordEntity record = achievementRecordRepository.save(AchievementRecordEntity.fromCompletedEvent(userId, event));
                    enqueueDerivedAnalysis(record);
                    profileSnapshotDirtyService.markDirty(userId, "completed-event");
                    return record;
                });
    }

    @Transactional
    public AchievementRecordEntity createFromChallenge(String userId, ChallengeEntity challenge) {
        return achievementRecordRepository.findByUserIdAndSourceTypeAndSourceId(userId, AchievementSourceType.CHALLENGE, challenge.getId())
                .map(record -> {
                    record.setDid(challenge.getDid());
                    record.setLearned(challenge.getLearned());
                    enqueueDerivedAnalysis(record);
                    profileSnapshotDirtyService.markDirty(userId, "updated-completed-challenge");
                    return record;
                })
                .orElseGet(() -> {
                    AchievementRecordEntity record = achievementRecordRepository.save(AchievementRecordEntity.fromCompletedChallenge(userId, challenge));
                    enqueueDerivedAnalysis(record);
                    profileSnapshotDirtyService.markDirty(userId, "completed-challenge");
                    return record;
                });
    }

    @Transactional(readOnly = true)
    public List<AchievementDtos.AchievementRecordResponse> history(String userId) {
        return historyEntities(userId).stream()
                .map(AchievementDtos.AchievementRecordResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<AchievementDtos.AchievementRecordResponse> historyPage(String userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        return PageResponse.from(
                achievementRecordRepository.findByUserIdOrderByCompletedAtDesc(userId, pageRequest),
                AchievementDtos.AchievementRecordResponse::from
        );
    }

    @Transactional(readOnly = true)
    public List<AchievementRecordEntity> historyEntities(String userId) {
        return achievementRecordRepository.findByUserIdOrderByCompletedAtDesc(userId);
    }

    @Transactional
    public AchievementDtos.AchievementRecordResponse updateReflection(String userId, Long recordId,
                                                                      AchievementDtos.ReflectionRequest request) {
        AchievementRecordEntity record = achievementRecordRepository.findByIdAndUserId(recordId, userId)
                .orElseThrow(() -> ApiException.notFound("历史记录不存在：" + recordId));
        record.setDid(blankToNull(request.did()));
        record.setLearned(blankToNull(request.learned()));
        enqueueDerivedAnalysis(record);
        profileSnapshotDirtyService.markDirty(userId, "updated-achievement-reflection");
        return AchievementDtos.AchievementRecordResponse.from(record);
    }

    private void enqueueDerivedAnalysis(AchievementRecordEntity record) {
        if (record == null || record.getId() == null) {
            return;
        }
        String sourceType = record.getSourceType() == null ? null : record.getSourceType().name();
        domainEventPublisher.publishGrowthTagExtraction(record.getId(), record.getUserId(), sourceType);
        domainEventPublisher.publishAbilityEvidenceAssessment(record.getId(), record.getUserId());
    }

    @Transactional(readOnly = true)
    public AchievementDtos.AchievementSummary summary(String userId) {
        List<AchievementRecordEntity> records = historyEntities(userId);
        Map<String, Long> categoryCounts = new LinkedHashMap<>();

        for (AchievementRecordEntity record : records) {
            categoryCounts.merge(record.getCategory(), 1L, Long::sum);
        }

        List<AchievementDtos.CategoryCount> categoryCountDtos = categoryCounts.entrySet().stream()
                .map(entry -> new AchievementDtos.CategoryCount(entry.getKey(), entry.getValue()))
                .toList();

        return new AchievementDtos.AchievementSummary(
                records.size(),
                categoryCounts.size(),
                categoryCountDtos,
                buildGrowthCurve(records)
        );
    }

    /**
     * 成长曲线不交给 AI 直接“画”，后端先算稳定的数据，前端负责可视化。
     */
    private List<AchievementDtos.GrowthPoint> buildGrowthCurve(List<AchievementRecordEntity> records) {
        List<AchievementRecordEntity> ordered = new ArrayList<>(records);
        ordered.sort((left, right) -> left.getCompletedAt().compareTo(right.getCompletedAt()));

        Map<String, Integer> running = new LinkedHashMap<>();
        running.put("沟通表达", 0);
        running.put("执行协作", 0);
        running.put("调研分析", 0);
        running.put("内容创作", 0);
        running.put("跨文化理解", 0);

        List<AchievementDtos.GrowthPoint> points = new ArrayList<>();

        for (int index = 0; index < ordered.size(); index++) {
            AchievementRecordEntity record = ordered.get(index);
            String text = textOf(record);
            running.computeIfPresent("沟通表达", (key, value) -> value + score(text, "日语", "沟通", "翻译", "引导", "陪伴"));
            running.computeIfPresent("执行协作", (key, value) -> value + score(text, "运营", "活动", "现场", "执行", "团队", "协作"));
            running.computeIfPresent("调研分析", (key, value) -> value + score(text, "研究", "调研", "访谈", "问卷", "数据", "复盘"));
            running.computeIfPresent("内容创作", (key, value) -> value + score(text, "内容", "文案", "写作", "记录", "编辑"));
            running.computeIfPresent("跨文化理解", (key, value) -> value + score(text, "跨文化", "文化", "留学生", "中文", "中日"));
            points.add(new AchievementDtos.GrowthPoint(index + 1, record.getCompletedAt(), new LinkedHashMap<>(running)));
        }

        return points;
    }

    private int score(String text, String... keywords) {
        int score = 1;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score += 2;
            }
        }
        return score;
    }

    private String textOf(AchievementRecordEntity record) {
        return String.join(" ",
                record.getEventTitle(),
                record.getOrganizationName(),
                record.getCategory(),
                record.getContent(),
                nullToEmpty(record.getSkill()),
                nullToEmpty(record.getDid()),
                nullToEmpty(record.getLearned())
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
