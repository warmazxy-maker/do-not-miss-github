package com.donotmiss.backend.capabilityv2;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, source-neutral input for the V2 capability pipeline.
 *
 * <p>Stages 06-10 only normalize and classify this object. They must not change a
 * student's score directly. The later score stage consumes the same input regardless of
 * whether it originated from an event, challenge, coach log, or resume interview.</p>
 */
public record CapabilityEvidenceInput(
        String schemaVersion,
        String evidenceId,
        String idempotencyKey,
        CapabilityEvidenceSourceKind sourceKind,
        String userId,
        String sourceId,
        String sourceType,
        BigDecimal sourceCredibilityPrior,
        String title,
        String description,
        String reflection,
        String structuredPayloadJson,
        String qualityReportJson,
        List<String> evidenceFacts,
        Instant occurredAt,
        boolean scoringEligible,
        String eligibilityReason,
        String evidenceHash
) {
    public CapabilityEvidenceInput {
        schemaVersion = defaultIfBlank(schemaVersion, "capability-evidence-v2");
        evidenceId = required(evidenceId, "evidenceId");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        userId = required(userId, "userId");
        sourceId = required(sourceId, "sourceId");
        sourceType = defaultIfBlank(sourceType, "OTHER");
        sourceCredibilityPrior = Objects.requireNonNull(sourceCredibilityPrior, "sourceCredibilityPrior");
        title = defaultIfBlank(title, "未命名证据");
        description = nullToEmpty(description);
        reflection = nullToEmpty(reflection);
        structuredPayloadJson = defaultIfBlank(structuredPayloadJson, "{}");
        qualityReportJson = defaultIfBlank(qualityReportJson, "{}");
        evidenceFacts = evidenceFacts == null ? List.of() : List.copyOf(evidenceFacts);
        eligibilityReason = defaultIfBlank(eligibilityReason, "待后续评分策略判定");
        evidenceHash = required(evidenceHash, "evidenceHash");
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
