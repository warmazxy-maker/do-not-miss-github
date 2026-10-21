package com.donotmiss.backend.mq;

import java.time.Instant;
import java.math.BigDecimal;

public final class DomainEventMessages {
    public static final String EVENT_INDEX_UPSERT = "UPSERT";
    public static final String EVENT_INDEX_DELETE = "DELETE";

    private DomainEventMessages() {
    }

    public record EventIndexMessage(Long eventId, String action, Instant occurredAt) {
        public EventIndexMessage {
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
        }
    }

    public record EventQualityAnalysisMessage(Long eventId, Instant occurredAt) {
        public EventQualityAnalysisMessage {
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
        }
    }

    public record AbilityEvidenceAssessmentMessage(Long recordId, String userId, Instant occurredAt) {
        public AbilityEvidenceAssessmentMessage {
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
        }
    }

    /**
     * Carries the evaluator output into the dual-label pipeline. Numeric values are
     * captured in the message so a retry uses the exact same assessed evidence.
     * {@code deferralCount} counts how often the message was postponed because the model was
     * unavailable; it is absent (null) in messages written before Stage 17.
     */
    public record CapabilityV2ScoringMessage(
            Long recordId,
            String userId,
            Long assessmentId,
            BigDecimal evidenceQuality,
            BigDecimal novelty,
            Instant occurredAt,
            Integer deferralCount
    ) {
        public CapabilityV2ScoringMessage {
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
            if (deferralCount == null) {
                deferralCount = 0;
            }
        }

        public CapabilityV2ScoringMessage deferredOnce() {
            return new CapabilityV2ScoringMessage(recordId, userId, assessmentId, evidenceQuality, novelty,
                    occurredAt, deferralCount + 1);
        }
    }

    public record UserProfileRefreshMessage(String userId, String reason, Instant occurredAt) {
        public UserProfileRefreshMessage {
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
        }
    }
}
