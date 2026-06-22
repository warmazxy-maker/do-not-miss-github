package com.donotmiss.backend.organization;

import java.time.Instant;

public class OrganizationDtos {
    public record OrganizationResponse(
            Long id,
            String name,
            String type,
            String summary,
            Instant createdAt
    ) {
        public static OrganizationResponse from(OrganizationEntity organization) {
            return new OrganizationResponse(
                    organization.getId(),
                    organization.getName(),
                    organization.getType(),
                    organization.getSummary(),
                    organization.getCreatedAt()
            );
        }
    }
}
