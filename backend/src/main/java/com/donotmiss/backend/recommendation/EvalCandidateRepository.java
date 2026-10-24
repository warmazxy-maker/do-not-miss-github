package com.donotmiss.backend.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvalCandidateRepository extends JpaRepository<EvalCandidateEntity, Long> {

    Optional<EvalCandidateEntity> findByImpressionId(Long impressionId);

    List<EvalCandidateEntity> findTop100ByStatusOrderByCreatedAtDesc(EvalCandidateStatus status);

    List<EvalCandidateEntity> findByStatusOrderByIdAsc(EvalCandidateStatus status);
}
