package com.donotmiss.backend.challenge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class ChallengeDtos {
    public record CreateChallengeRequest(
            @NotBlank String title,
            @NotBlank String category,
            @NotBlank String goal,
            @NotBlank String description
    ) {
    }

    public record CompleteChallengeRequest(
            @Size(max = 2000) String did,
            @Size(max = 2000) String learned
    ) {
    }

    public record ChallengeResponse(
            Long id,
            String title,
            String category,
            String goal,
            String description,
            String status,
            Instant createdAt,
            Instant completedAt,
            String did,
            String learned
    ) {
        public static ChallengeResponse from(ChallengeEntity challenge) {
            return new ChallengeResponse(
                    challenge.getId(),
                    challenge.getTitle(),
                    challenge.getCategory(),
                    challenge.getGoal(),
                    challenge.getDescription(),
                    challenge.getStatus().name(),
                    challenge.getCreatedAt(),
                    challenge.getCompletedAt(),
                    challenge.getDid(),
                    challenge.getLearned()
            );
        }
    }
}
