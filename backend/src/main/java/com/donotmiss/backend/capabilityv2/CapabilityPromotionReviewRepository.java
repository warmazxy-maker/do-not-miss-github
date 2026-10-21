package com.donotmiss.backend.capabilityv2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapabilityPromotionReviewRepository extends JpaRepository<CapabilityPromotionReviewEntity, Long> {
    Optional<CapabilityPromotionReviewEntity> findByOpenKey(String openKey);

    List<CapabilityPromotionReviewEntity> findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(String userId, Long bucketId);
}
