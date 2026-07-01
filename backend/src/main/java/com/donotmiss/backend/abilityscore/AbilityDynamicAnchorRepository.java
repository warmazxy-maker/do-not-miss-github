package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AbilityDynamicAnchorRepository extends JpaRepository<AbilityDynamicAnchorEntity, Long> {
    List<AbilityDynamicAnchorEntity> findByStatusOrderByConfidenceDescUpdatedAtDesc(String status);

    Optional<AbilityDynamicAnchorEntity> findByNormalizedKey(String normalizedKey);
}
