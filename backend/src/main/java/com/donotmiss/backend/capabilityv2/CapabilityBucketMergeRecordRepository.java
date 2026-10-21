package com.donotmiss.backend.capabilityv2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CapabilityBucketMergeRecordRepository extends JpaRepository<CapabilityBucketMergeRecordEntity, Long> {
    List<CapabilityBucketMergeRecordEntity> findByTargetBucketIdOrderByCreatedAtDesc(Long targetBucketId);
}
