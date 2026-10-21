package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.achievement.AchievementSourceType;
import com.donotmiss.backend.event.BenefitType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbilityEvidenceTimelineServiceTest {
    @Test
    void buildsEvidenceDirectlyFromAchievementRecord() {
        UserAbilityStateRepository stateRepository = mock(UserAbilityStateRepository.class);
        AbilityScoreResultRepository resultRepository = mock(AbilityScoreResultRepository.class);
        AchievementRecordRepository recordRepository = mock(AchievementRecordRepository.class);
        AbilityEvidenceTimelineService service = new AbilityEvidenceTimelineService(
                stateRepository,
                resultRepository,
                recordRepository
        );

        UserAbilityStateEntity state = state("user-1", "Applied LLM Integration");
        AbilityScoreResultEntity result = scoreResult(18L, AbilityScoreResultStatus.REVIEW_REQUIRED);
        AchievementRecordEntity record = record("user-1", 18L);
        when(stateRepository.findById(42L)).thenReturn(Optional.of(state));
        when(resultRepository.findByAbilityStateIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(result));
        when(recordRepository.findAllById(anyCollection())).thenReturn(List.of(record));

        AbilityEvidenceTimelineService.AbilityEvidenceTimelineResponse response =
                service.timeline("user-1", 42L);

        assertThat(response.achievementRecordIds()).containsExactly(18L);
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.evidenceCount()).isEqualTo(1);
        assertThat(response.evidences().getFirst().title()).isEqualTo("Java 零基础入门训练营");
    }

    @Test
    void ignoresRecordsFromOtherUsers() {
        UserAbilityStateRepository stateRepository = mock(UserAbilityStateRepository.class);
        AbilityScoreResultRepository resultRepository = mock(AbilityScoreResultRepository.class);
        AchievementRecordRepository recordRepository = mock(AchievementRecordRepository.class);
        AbilityEvidenceTimelineService service = new AbilityEvidenceTimelineService(
                stateRepository,
                resultRepository,
                recordRepository
        );

        when(stateRepository.findById(42L)).thenReturn(Optional.of(state("user-1", "Applied LLM Integration")));
        when(resultRepository.findByAbilityStateIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of(scoreResult(18L, AbilityScoreResultStatus.REVIEW_REQUIRED)));
        AchievementRecordEntity record = record("user-2", 18L);
        when(recordRepository.findAllById(anyCollection())).thenReturn(List.of(record));

        AbilityEvidenceTimelineService.AbilityEvidenceTimelineResponse response = service.timeline("user-1", 42L);
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.evidenceCount()).isZero();
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
        result.setVerifiedExperienceGain(new BigDecimal("1.5"));
        return result;
    }

    private AchievementRecordEntity record(String userId, Long id) {
        AchievementRecordEntity record = mock(AchievementRecordEntity.class);
        when(record.getId()).thenReturn(id);
        when(record.getUserId()).thenReturn(userId);
        when(record.getSourceType()).thenReturn(AchievementSourceType.EVENT);
        when(record.getSourceId()).thenReturn(100L);
        when(record.getEventTitle()).thenReturn("Java 零基础入门训练营");
        when(record.getContent()).thenReturn("通过小项目学习 Java 基础语法和函数设计。");
        when(record.getSkill()).thenReturn("Java 基础、函数设计");
        when(record.getDid()).thenReturn("完成命令行小项目");
        when(record.getLearned()).thenReturn("理解函数拆分");
        when(record.getCompletedAt()).thenReturn(Instant.parse("2026-06-25T02:24:00Z"));
        return record;
    }
}
