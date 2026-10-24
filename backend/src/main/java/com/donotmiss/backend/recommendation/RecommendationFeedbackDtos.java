package com.donotmiss.backend.recommendation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class RecommendationFeedbackDtos {
    private RecommendationFeedbackDtos() {
    }

    /** {@code type} null clears the student's verdict on this event. */
    public record SubmitRequest(
            @NotNull Long runId,
            @NotNull Long eventId,
            RecommendationFeedbackType type,
            @Size(max = 500) String reason
    ) {
    }

    /** One reaction, with enough about the event for the profile agent to see a pattern. */
    public record FeedbackSignal(
            RecommendationFeedbackType type,
            boolean positive,
            boolean explicit,
            Long eventId,
            String eventTitle,
            String category,
            String location,
            String skill,
            String reason,
            Instant at
    ) {
    }

    public record FeedbackState(
            Long eventId,
            RecommendationFeedbackType type,
            String reason
    ) {
    }
}
