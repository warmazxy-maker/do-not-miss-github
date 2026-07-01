package com.donotmiss.backend.mq;

import java.time.Instant;

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

    public record GrowthTagExtractionMessage(Long recordId, String userId, String sourceType, Instant occurredAt) {
        public GrowthTagExtractionMessage {
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

    public record UserProfileRefreshMessage(String userId, String reason, Instant occurredAt) {
        public UserProfileRefreshMessage {
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
        }
    }
}
