package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AbilityEvidenceAssessmentRepository extends JpaRepository<AbilityEvidenceAssessmentEntity, Long> {
    Optional<AbilityEvidenceAssessmentEntity> findByJobId(Long jobId);

    Optional<AbilityEvidenceAssessmentEntity> findTopByEvidenceHashOrderByCreatedAtDesc(String evidenceHash);
}
