package com.donotmiss.backend.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns business actions into implicit recommendation feedback.
 *
 * <p>{@code AFTER_COMMIT}: the feedback is written only if the reservation / follow really
 * committed, and a failure here is logged and swallowed, so analytics can never break or roll back
 * the student's action. {@code fallbackExecution}: callers without a transaction still get it.</p>
 */
@Component
public class RecommendationActivityListener {
    private static final Logger log = LoggerFactory.getLogger(RecommendationActivityListener.class);

    private final RecommendationFeedbackService feedbackService;

    public RecommendationActivityListener(RecommendationFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivity(RecommendationActivityEvent activity) {
        try {
            feedbackService.recordImplicit(activity);
        } catch (RuntimeException ex) {
            log.warn("Could not record implicit recommendation feedback. activity={}, reason={}", activity, ex.getMessage());
        }
    }
}
