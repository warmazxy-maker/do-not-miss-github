package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AbilityScoreResultRepository extends JpaRepository<AbilityScoreResultEntity, Long> {
    Optional<AbilityScoreResultEntity> findByRequestIdAndNormalizedDimension(
            String requestId,
            String normalizedDimension
    );

    Optional<AbilityScoreResultEntity> findByAssessmentIdAndNormalizedDimensionAndScoringRuleVersion(
            Long assessmentId,
            String normalizedDimension,
            String scoringRuleVersion
    );

    Optional<AbilityScoreResultEntity> findTopByAchievementRecordIdAndNormalizedDimensionAndScoringRuleVersionOrderByCreatedAtDesc(
            Long achievementRecordId,
            String normalizedDimension,
            String scoringRuleVersion
    );

    Optional<AbilityScoreResultEntity> findTopByAchievementRecordIdAndNormalizedDimensionAndScoringRuleVersionAndStatusNotOrderByCreatedAtDesc(
            Long achievementRecordId,
            String normalizedDimension,
            String scoringRuleVersion,
            AbilityScoreResultStatus status
    );

    Optional<AbilityScoreResultEntity> findFirstByAbilityStateIdAndStatusNotOrderByCreatedAtDesc(
            Long abilityStateId,
            AbilityScoreResultStatus status
    );

    List<AbilityScoreResultEntity> findByAbilityStateIdOrderByCreatedAtDesc(Long abilityStateId);

    List<AbilityScoreResultEntity> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
}
