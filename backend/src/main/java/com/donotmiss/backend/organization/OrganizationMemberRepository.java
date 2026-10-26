package com.donotmiss.backend.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMemberEntity, Long> {

    boolean existsByOrganizationIdAndUserId(Long organizationId, String userId);

    boolean existsByOrganizationId(Long organizationId);

    List<OrganizationMemberEntity> findByUserId(String userId);
}
