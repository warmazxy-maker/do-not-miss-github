package com.donotmiss.backend.recommendation;

/**
 * How a student reacted to a recommended event.
 * Explicit types are clicks on the recommendation card; implicit types are inferred from what the
 * student did afterwards, within the attribution window.
 */
public enum RecommendationFeedbackType {
    INTERESTED(true, true),
    NOT_RELEVANT(true, false),
    RESERVED(false, true),
    RESERVATION_CANCELLED(false, false),
    COMPLETED(false, true),
    FOLLOWED(false, true);

    private final boolean explicit;
    private final boolean positive;

    RecommendationFeedbackType(boolean explicit, boolean positive) {
        this.explicit = explicit;
        this.positive = positive;
    }

    public boolean explicit() {
        return explicit;
    }

    public boolean positive() {
        return positive;
    }
}
