package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AbilityScoreAppealRepository extends JpaRepository<AbilityScoreAppealEntity, Long> {
    List<AbilityScoreAppealEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<AbilityScoreAppealEntity> findByIdAndUserId(Long id, String userId);

    Optional<AbilityScoreAppealEntity> findByScoreResultIdAndReplacementAssessmentIdAndNormalizedDimensionAndRequestType(
            Long scoreResultId,
            Long replacementAssessmentId,
            String normalizedDimension,
            AbilityScoreAppealType requestType
    );
}
