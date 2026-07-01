package com.donotmiss.backend.abilityscore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAbilityStateRepository extends JpaRepository<UserAbilityStateEntity, Long> {
    Optional<UserAbilityStateEntity> findByUserIdAndNormalizedDimension(String userId, String normalizedDimension);

    List<UserAbilityStateEntity> findByUserIdOrderByAbilityScoreDesc(String userId);
}
