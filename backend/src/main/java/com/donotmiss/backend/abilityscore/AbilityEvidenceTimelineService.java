package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.achievement.AchievementDtos;
import com.donotmiss.backend.achievement.GrowthTagEvidenceEntity;
import com.donotmiss.backend.achievement.GrowthTagEvidenceRepository;
import com.donotmiss.backend.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AbilityEvidenceTimelineService {
    private final UserAbilityStateRepository stateRepository;
    private final AbilityScoreResultRepository resultRepository;
    private final GrowthTagEvidenceRepository evidenceRepository;

    public AbilityEvidenceTimelineService(UserAbilityStateRepository stateRepository,
                                          AbilityScoreResultRepository resultRepository,
                                          GrowthTagEvidenceRepository evidenceRepository) {
        this.stateRepository = stateRepository;
        this.resultRepository = resultRepository;
        this.evidenceRepository = evidenceRepository;
    }

    @Transactional(readOnly = true)
    public AbilityEvidenceTimelineResponse timeline(String userId, Long abilityStateId) {
        UserAbilityStateEntity state = stateRepository.findById(abilityStateId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("能力状态不存在：" + abilityStateId));

        Set<Long> recordIds = new LinkedHashSet<>();
        resultRepository.findByAbilityStateIdOrderByCreatedAtDesc(abilityStateId).stream()
                .filter(result -> result.getStatus() != AbilityScoreResultStatus.SUPERSEDED)
                .map(AbilityScoreResultEntity::getAchievementRecordId)
                .forEach(recordIds::add);

        List<AchievementDtos.GrowthTagEvidenceResponse> evidences = recordIds.isEmpty()
                ? List.of()
                : evidenceRepository
                        .findByRecord_IdInAndUserIdOrderByOccurredAtAsc(recordIds, userId)
                        .stream()
                        .map(AchievementDtos.GrowthTagEvidenceResponse::from)
                        .toList();

        return new AbilityEvidenceTimelineResponse(
                state.getId(),
                state.getDimensionName(),
                List.copyOf(recordIds),
                evidences,
                recordIds.size(),
                evidences.size(),
                recordIds.isEmpty() ? "NO_SCORE_RECORD" : evidences.isEmpty() ? "TAG_EXTRACTION_PENDING" : "READY"
        );
    }

    public record AbilityEvidenceTimelineResponse(
            Long abilityStateId,
            String dimension,
            List<Long> achievementRecordIds,
            List<AchievementDtos.GrowthTagEvidenceResponse> evidences,
            int scoredRecordCount,
            int evidenceCount,
            String status
    ) {
    }
}
