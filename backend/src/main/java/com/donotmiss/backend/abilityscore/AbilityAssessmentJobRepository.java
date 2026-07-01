package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AbilityAssessmentJobRepository extends JpaRepository<AbilityAssessmentJobEntity, Long> {
    Optional<AbilityAssessmentJobEntity> findByRequestId(String requestId);

    List<AbilityAssessmentJobEntity> findTop30ByUserIdOrderByCreatedAtDesc(String userId);

    boolean existsByAchievementRecordIdAndEvidenceHashAndPromptVersionAndRubricVersion(
            Long achievementRecordId,
            String evidenceHash,
            String promptVersion,
            String rubricVersion
    );

    Optional<AbilityAssessmentJobEntity> findByAchievementRecordIdAndEvidenceHashAndPromptVersionAndRubricVersion(
            Long achievementRecordId,
            String evidenceHash,
            String promptVersion,
            String rubricVersion
    );

    Optional<AbilityAssessmentJobEntity> findFirstByUserIdAndContentFingerprintAndAchievementRecordIdNotOrderByCreatedAtDesc(
            String userId,
            String contentFingerprint,
            Long achievementRecordId
    );
}
