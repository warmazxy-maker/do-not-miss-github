package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.achievement.GrowthTagEvidenceEntity;
import com.donotmiss.backend.achievement.GrowthTagEvidenceRepository;
import com.donotmiss.backend.achievement.GrowthTagEntity;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbilityEvidenceTimelineServiceTest {
    @Test
    void linksEvidenceByAchievementRecordInsteadOfDimensionName() {
        UserAbilityStateRepository stateRepository = mock(UserAbilityStateRepository.class);
        AbilityScoreResultRepository resultRepository = mock(AbilityScoreResultRepository.class);
        GrowthTagEvidenceRepository evidenceRepository = mock(GrowthTagEvidenceRepository.class);
        AbilityEvidenceTimelineService service = new AbilityEvidenceTimelineService(
                stateRepository,
                resultRepository,
                evidenceRepository
        );

        UserAbilityStateEntity state = state("user-1", "Applied LLM Integration");
        AbilityScoreResultEntity result = scoreResult(18L, AbilityScoreResultStatus.REVIEW_REQUIRED);
        when(stateRepository.findById(42L)).thenReturn(Optional.of(state));
        when(resultRepository.findByAbilityStateIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(result));
        GrowthTagEvidenceEntity evidence = mock(GrowthTagEvidenceEntity.class);
        GrowthTagEntity tag = mock(GrowthTagEntity.class);
        AchievementRecordEntity record = mock(AchievementRecordEntity.class);
        when(tag.getId()).thenReturn(9L);
        when(record.getId()).thenReturn(18L);
        when(evidence.getTag()).thenReturn(tag);
        when(evidence.getRecord()).thenReturn(record);
        when(evidence.getSourceType()).thenReturn(AchievementSourceType.EVENT);
        when(evidenceRepository.findByRecord_IdInAndUserIdOrderByOccurredAtAsc(anyCollection(), eq("user-1")))
                .thenReturn(List.of(evidence));

        AbilityEvidenceTimelineService.AbilityEvidenceTimelineResponse response =
                service.timeline("user-1", 42L);

        assertThat(response.achievementRecordIds()).containsExactly(18L);
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.evidenceCount()).isEqualTo(1);
    }

    @Test
    void reportsPendingWhenScoringFinishedBeforeGrowthTagExtraction() {
        UserAbilityStateRepository stateRepository = mock(UserAbilityStateRepository.class);
        AbilityScoreResultRepository resultRepository = mock(AbilityScoreResultRepository.class);
        GrowthTagEvidenceRepository evidenceRepository = mock(GrowthTagEvidenceRepository.class);
        AbilityEvidenceTimelineService service = new AbilityEvidenceTimelineService(
                stateRepository,
                resultRepository,
                evidenceRepository
        );

        when(stateRepository.findById(42L)).thenReturn(Optional.of(state("user-1", "Applied LLM Integration")));
        when(resultRepository.findByAbilityStateIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of(scoreResult(18L, AbilityScoreResultStatus.REVIEW_REQUIRED)));
        when(evidenceRepository.findByRecord_IdInAndUserIdOrderByOccurredAtAsc(anyCollection(), eq("user-1")))
                .thenReturn(List.of());

        assertThat(service.timeline("user-1", 42L).status()).isEqualTo("TAG_EXTRACTION_PENDING");
    }

    private UserAbilityStateEntity state(String userId, String dimension) {
        UserAbilityStateEntity state = new UserAbilityStateEntity();
        state.setUserId(userId);
        state.setDimensionName(dimension);
        state.setNormalizedDimension("llm-integration");
        return state;
    }

    private AbilityScoreResultEntity scoreResult(Long recordId, AbilityScoreResultStatus status) {
        AbilityScoreResultEntity result = new AbilityScoreResultEntity();
        result.setAchievementRecordId(recordId);
        result.setStatus(status);
        return result;
    }
}
