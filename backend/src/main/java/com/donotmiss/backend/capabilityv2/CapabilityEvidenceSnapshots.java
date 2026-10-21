package com.donotmiss.backend.capabilityv2;

import java.util.LinkedHashMap;
import java.util.Map;

/** The evidence fields written to audits and traces, kept identical for every agent. */
final class CapabilityEvidenceSnapshots {
    private CapabilityEvidenceSnapshots() {
    }

    static Map<String, Object> of(CapabilityEvidenceInput evidence) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", evidence.schemaVersion());
        snapshot.put("evidenceId", evidence.evidenceId());
        snapshot.put("idempotencyKey", evidence.idempotencyKey());
        snapshot.put("sourceKind", evidence.sourceKind().name());
        snapshot.put("userId", evidence.userId());
        snapshot.put("sourceId", evidence.sourceId());
        snapshot.put("sourceType", evidence.sourceType());
        snapshot.put("sourceCredibilityPrior", evidence.sourceCredibilityPrior());
        snapshot.put("title", evidence.title());
        snapshot.put("description", evidence.description());
        snapshot.put("reflection", evidence.reflection());
        snapshot.put("structuredPayloadJson", evidence.structuredPayloadJson());
        snapshot.put("qualityReportJson", evidence.qualityReportJson());
        snapshot.put("evidenceFacts", evidence.evidenceFacts());
        snapshot.put("occurredAt", evidence.occurredAt() == null ? "" : evidence.occurredAt().toString());
        snapshot.put("scoringEligible", evidence.scoringEligible());
        snapshot.put("eligibilityReason", evidence.eligibilityReason());
        snapshot.put("evidenceHash", evidence.evidenceHash());
        return snapshot;
    }
}
