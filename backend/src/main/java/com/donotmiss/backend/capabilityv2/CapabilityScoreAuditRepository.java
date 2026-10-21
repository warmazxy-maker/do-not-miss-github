package com.donotmiss.backend.capabilityv2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapabilityScoreAuditRepository extends JpaRepository<CapabilityScoreAuditEntity, Long> {
    Optional<CapabilityScoreAuditEntity> findByEvidenceLinkIdAndScoringRuleVersion(Long evidenceLinkId, String scoringRuleVersion);

    List<CapabilityScoreAuditEntity> findTop100ByUserIdOrderByCreatedAtDesc(String userId);

    List<CapabilityScoreAuditEntity> findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(String userId, Long bucketId);
}
