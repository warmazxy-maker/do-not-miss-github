package com.donotmiss.backend.follow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FollowRepository extends JpaRepository<FollowEntity, Long> {
    List<FollowEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<FollowEntity> findByUserIdAndOrganizationName(String userId, String organizationName);
}
