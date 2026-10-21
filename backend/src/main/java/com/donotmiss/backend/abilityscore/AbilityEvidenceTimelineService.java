package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AbilityEvidenceTimelineService {
    private final UserAbilityStateRepository stateRepository;
    private final AbilityScoreResultRepository resultRepository;
    private final AchievementRecordRepository recordRepository;

    public AbilityEvidenceTimelineService(UserAbilityStateRepository stateRepository,
                                          AbilityScoreResultRepository resultRepository,
                                          AchievementRecordRepository recordRepository) {
        this.stateRepository = stateRepository;
        this.resultRepository = resultRepository;
        this.recordRepository = recordRepository;
    }

    @Transactional(readOnly = true)
    public AbilityEvidenceTimelineResponse timeline(String userId, Long abilityStateId) {
        UserAbilityStateEntity state = stateRepository.findById(abilityStateId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("能力状态不存在：" + abilityStateId));

        Map<Long, AbilityScoreResultEntity> latestResultByRecordId = new LinkedHashMap<>();
        resultRepository.findByAbilityStateIdOrderByCreatedAtDesc(abilityStateId).stream()
                .filter(result -> result.getStatus() != AbilityScoreResultStatus.SUPERSEDED)
                .forEach(result -> latestResultByRecordId.putIfAbsent(result.getAchievementRecordId(), result));

        List<Long> recordIds = new ArrayList<>(latestResultByRecordId.keySet());
        Map<Long, AchievementRecordEntity> recordsById = new LinkedHashMap<>();
        recordRepository.findAllById(recordIds).stream()
                .filter(record -> userId.equals(record.getUserId()))
                .forEach(record -> recordsById.put(record.getId(), record));

        List<AbilityEvidenceItem> evidences = recordsById.values().stream()
                .sorted(Comparator.comparing(AchievementRecordEntity::getCompletedAt))
                .map(record -> toEvidenceItem(state.getId(), record, latestResultByRecordId.get(record.getId())))
                .toList();

        return new AbilityEvidenceTimelineResponse(
                state.getId(),
                state.getDimensionName(),
                recordIds,
                evidences,
                recordIds.size(),
                evidences.size(),
                recordIds.isEmpty() ? "NO_SCORE_RECORD" : "READY"
        );
    }

    private AbilityEvidenceItem toEvidenceItem(Long abilityStateId,
                                               AchievementRecordEntity record,
                                               AbilityScoreResultEntity result) {
        BigDecimal scoreDelta = result == null || result.getVerifiedExperienceGain() == null
                ? BigDecimal.ZERO
                : result.getVerifiedExperienceGain();
        return new AbilityEvidenceItem(
                result == null ? record.getId() : result.getId(),
                abilityStateId,
                record.getId(),
                record.getSourceType().name(),
                record.getSourceId(),
                record.getEventTitle(),
                evidenceSummary(record),
                record.getDid(),
                record.getLearned(),
                scoreDelta,
                false,
                null,
                record.getCompletedAt()
        );
    }

    private String evidenceSummary(AchievementRecordEntity record) {
        StringBuilder summary = new StringBuilder(record.getContent() == null ? "" : record.getContent());
        if (record.getSkill() != null && !record.getSkill().isBlank()) {
            if (!summary.isEmpty()) {
                summary.append(" ");
            }
            summary.append("技能：").append(record.getSkill());
        }
        return summary.toString();
    }

    public record AbilityEvidenceTimelineResponse(
            Long abilityStateId,
            String dimension,
            List<Long> achievementRecordIds,
            List<AbilityEvidenceItem> evidences,
            int scoredRecordCount,
            int evidenceCount,
            String status
    ) {
    }

    public record AbilityEvidenceItem(
            Long id,
            Long tagId,
            Long recordId,
            String sourceType,
            Long sourceId,
            String title,
            String summary,
            String did,
            String learned,
            BigDecimal scoreDelta,
            boolean milestone,
            String milestoneReason,
            Instant occurredAt
    ) {
    }
}
