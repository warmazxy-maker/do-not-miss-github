package com.donotmiss.backend.achievement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GrowthTagEvidenceRepository extends JpaRepository<GrowthTagEvidenceEntity, Long> {
    List<GrowthTagEvidenceEntity> findByUserIdOrderByOccurredAtDesc(String userId);

    List<GrowthTagEvidenceEntity> findByTag_IdAndUserIdOrderByOccurredAtAsc(Long tagId, String userId);

    List<GrowthTagEvidenceEntity> findByRecord_IdAndUserId(Long recordId, String userId);

    Optional<GrowthTagEvidenceEntity> findByTag_IdAndRecord_Id(Long tagId, Long recordId);

    Optional<GrowthTagEvidenceEntity> findByIdAndUserId(Long id, String userId);

    long countByTag_Id(Long tagId);

    void deleteByUserId(String userId);
}
