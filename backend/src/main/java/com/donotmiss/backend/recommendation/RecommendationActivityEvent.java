package com.donotmiss.backend.recommendation;

/**
 * A student action that may answer an earlier recommendation, published by the business service
 * that performed it. Exactly one of {@code eventId} (reserve, cancel, complete) or
 * {@code organizationName} (follow) is set.
 */
public record RecommendationActivityEvent(
        String userId,
        Long eventId,
        String organizationName,
        RecommendationFeedbackType type
) {
    public static RecommendationActivityEvent forEvent(String userId, Long eventId, RecommendationFeedbackType type) {
        return new RecommendationActivityEvent(userId, eventId, null, type);
    }

    public static RecommendationActivityEvent forOrganization(String userId, String organizationName) {
        return new RecommendationActivityEvent(userId, null, organizationName, RecommendationFeedbackType.FOLLOWED);
    }
}
