package com.donotmiss.backend.eventquality;

import java.time.Instant;
import java.util.List;

public class EventQualityDtos {
    public record AbilityImpact(
            String tag,
            Integer score,
            Double confidence,
            String evidence
    ) {
    }

    public record QualityModelResponse(
            Integer qualityScore,
            String reviewSuggestion,
            String difficulty,
            String summary,
            List<String> targetStudents,
            List<String> prerequisites,
            List<String> learningOutcomes,
            List<String> extractedTags,
            List<AbilityImpact> abilityImpacts,
            List<String> riskFlags,
            List<String> missingFields,
            Double confidence
    ) {
    }

    public record ReportResponse(
            Long id,
            Long eventId,
            int qualityScore,
            String qualityLevel,
            String reviewSuggestion,
            String difficulty,
            String summary,
            List<String> targetStudents,
            List<String> prerequisites,
            List<String> learningOutcomes,
            List<String> extractedTags,
            List<AbilityImpact> abilityImpacts,
            List<String> riskFlags,
            List<String> missingFields,
            List<Long> duplicateEventIds,
            String modelName,
            double confidence,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
