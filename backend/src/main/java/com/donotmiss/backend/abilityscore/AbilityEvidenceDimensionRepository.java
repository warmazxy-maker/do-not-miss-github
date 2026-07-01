package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AbilityEvidenceDimensionRepository extends JpaRepository<AbilityEvidenceDimensionEntity, Long> {
    List<AbilityEvidenceDimensionEntity> findByAssessmentIdOrderByRelevanceDesc(Long assessmentId);

    Optional<AbilityEvidenceDimensionEntity> findByAssessmentIdAndNormalizedDimension(
            Long assessmentId,
            String normalizedDimension
    );
}
