package com.donotmiss.backend.memory;

import java.time.Instant;
import java.util.List;

public class UserMemoryDtos {
    public record Profile(
            String summary,
            List<String> strengths,
            List<String> preferredCategories,
            List<String> preferredLocations,
            List<String> benefitPreferences,
            List<String> evidenceKeywords,
            List<MemorySignal> recentSignals,
            long completedCount,
            long activeChallengeCount,
            long coachLogCount,
            // What the student took up / turned down among past recommendations (B3).
            List<String> recommendationLikes,
            List<String> recommendationAvoids
    ) {
    }

    public record MemorySignal(
            String type,
            String title,
            String category,
            String detail,
            Instant occurredAt
    ) {
    }

    public record ProfileModelResponse(
            String summary,
            List<String> strengths,
            List<String> preferredCategories,
            List<String> preferredLocations,
            List<String> benefitPreferences,
            List<String> evidenceKeywords,
            List<MemorySignalModel> recentSignals,
            List<String> recommendationLikes,
            List<String> recommendationAvoids
    ) {
    }

    public record MemorySignalModel(
            String type,
            String title,
            String category,
            String detail,
            String occurredAt
    ) {
    }
}
