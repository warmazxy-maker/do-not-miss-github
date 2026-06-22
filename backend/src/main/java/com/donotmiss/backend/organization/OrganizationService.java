package com.donotmiss.backend.organization;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrganizationService {
    private final OrganizationRepository organizationRepository;

    public OrganizationService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    @Transactional
    public OrganizationEntity ensureOrganization(String name) {
        return organizationRepository.findByName(name)
                .orElseGet(() -> {
                    OrganizationEntity organization = new OrganizationEntity();
                    organization.setName(name);
                    organization.setType("发布组织");
                    organization.setSummary(name + " 发布的社会实践机会。");
                    return organizationRepository.save(organization);
                });
    }

    @Transactional(readOnly = true)
    public List<OrganizationDtos.OrganizationResponse> list() {
        return organizationRepository.findAll().stream()
                .map(OrganizationDtos.OrganizationResponse::from)
                .toList();
    }
}
