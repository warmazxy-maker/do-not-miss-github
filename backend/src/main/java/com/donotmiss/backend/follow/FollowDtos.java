package com.donotmiss.backend.follow;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public class FollowDtos {
    public record FollowRequest(@NotBlank String organizationName) {
    }

    public record FollowResponse(Long id, String organizationName, Instant createdAt) {
        public static FollowResponse from(FollowEntity follow) {
            return new FollowResponse(follow.getId(), follow.getOrganizationName(), follow.getCreatedAt());
        }
    }
}
