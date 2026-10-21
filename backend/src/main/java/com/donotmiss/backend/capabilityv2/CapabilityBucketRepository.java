package com.donotmiss.backend.capabilityv2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapabilityBucketRepository extends JpaRepository<CapabilityBucketEntity, Long> {
    Optional<CapabilityBucketEntity> findByBucketTypeAndOwnerUserIdAndNormalizedName(
            CapabilityBucketType bucketType,
            String ownerUserId,
            String normalizedName
    );

    List<CapabilityBucketEntity> findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
            CapabilityBucketType bucketType,
            String ownerUserId,
            CapabilityBucketStatus status
    );
}
