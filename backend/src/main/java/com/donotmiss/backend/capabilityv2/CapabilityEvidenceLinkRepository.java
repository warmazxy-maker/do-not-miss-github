package com.donotmiss.backend.capabilityv2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapabilityEvidenceLinkRepository extends JpaRepository<CapabilityEvidenceLinkEntity, Long> {
    Optional<CapabilityEvidenceLinkEntity> findByBucketIdAndIdempotencyKey(Long bucketId, String idempotencyKey);

    boolean existsByUserIdAndSourceTypeAndSourceId(String userId, String sourceType, String sourceId);

    List<CapabilityEvidenceLinkEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CapabilityEvidenceLinkEntity> findBySourceTypeAndSourceId(String sourceType, String sourceId);

    List<CapabilityEvidenceLinkEntity> findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(String userId, Long bucketId);
}
