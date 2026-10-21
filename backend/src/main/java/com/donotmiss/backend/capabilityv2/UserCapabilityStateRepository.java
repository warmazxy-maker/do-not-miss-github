package com.donotmiss.backend.capabilityv2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserCapabilityStateRepository extends JpaRepository<UserCapabilityStateEntity, Long> {
    Optional<UserCapabilityStateEntity> findByUserIdAndBucketId(String userId, Long bucketId);

    List<UserCapabilityStateEntity> findByUserIdOrderByAbilityScoreDescUpdatedAtDesc(String userId);
}
