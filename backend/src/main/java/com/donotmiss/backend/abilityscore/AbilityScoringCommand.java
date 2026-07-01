package com.donotmiss.backend.abilityscore;

import java.math.BigDecimal;

public record AbilityScoringCommand(
        Long assessmentId,
        AbilityEvidenceSourceType sourceType,
        BigDecimal evidenceQuality,
        BigDecimal novelty,
        String historySnapshotVersion
) {
}
