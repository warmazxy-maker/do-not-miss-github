package com.donotmiss.backend.capabilityv2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapabilityClassificationAuditRepository extends JpaRepository<CapabilityClassificationAuditEntity, Long> {
    Optional<CapabilityClassificationAuditEntity> findByRequestId(String requestId);

    List<CapabilityClassificationAuditEntity> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
}
