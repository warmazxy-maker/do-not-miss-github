package com.donotmiss.backend.abilityscore;

import java.math.BigDecimal;
import java.util.List;

public final class AbilityClusterModels {
    private AbilityClusterModels() {
    }

    public record AbilityClusterResponse(
            String clusterKey,
            String name,
            BigDecimal abilityScore,
            BigDecimal experienceScore,
            BigDecimal abilityUncertainty,
            String rank,
            int memberCount,
            String algorithmVersion,
            List<AbilityClusterMemberResponse> members,
            String anchorKey,
            String anchorSource,
            String anchorStatus
    ) {
    }

    public record AbilityClusterMemberResponse(
            Long abilityStateId,
            String dimension,
            String normalizedDimension,
            BigDecimal experienceScore,
            BigDecimal abilityScore,
            BigDecimal abilityUncertainty,
            String rank,
            BigDecimal similarityToCluster
    ) {
    }
}
