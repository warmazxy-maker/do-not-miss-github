package com.donotmiss.backend.achievement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GrowthTagRepository extends JpaRepository<GrowthTagEntity, Long> {
    List<GrowthTagEntity> findByUserIdOrderByScoreDescLastUpdatedAtDesc(String userId);

    Optional<GrowthTagEntity> findByUserIdAndNormalizedName(String userId, String normalizedName);

    Optional<GrowthTagEntity> findByIdAndUserId(Long id, String userId);

    void deleteByUserId(String userId);
}
