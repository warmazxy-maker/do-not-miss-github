package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JudgeAssessmentRepository extends JpaRepository<JudgeAssessmentEntity, Long> {
    Optional<JudgeAssessmentEntity> findByRequestId(String requestId);

    Optional<JudgeAssessmentEntity> findByScoreResultId(Long scoreResultId);

    List<JudgeAssessmentEntity> findTop30ByUserIdOrderByCreatedAtDesc(String userId);

    List<JudgeAssessmentEntity> findByAbilityStateIdOrderByCreatedAtDesc(Long abilityStateId);
}
