package com.donotmiss.backend.capabilityv2;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model for the Stage 14 ability portfolio.
 *
 * <p>The scoring pipeline owns writes to V2 tables. This service only joins a user's
 * score states with their bucket metadata, so opening the portfolio never triggers
 * classification, score recalculation, or an external model call.</p>
 */
@Service
@Transactional(readOnly = true)
public class CapabilityV2ProfileService {
    private static final Comparator<CapabilitySummary> SUMMARY_ORDER = Comparator
            .comparing(CapabilitySummary::abilityScore, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CapabilitySummary::experienceScore, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CapabilitySummary::displayName, Comparator.nullsLast(String::compareTo));

    private final UserCapabilityStateRepository stateRepository;
    private final CapabilityBucketRepository bucketRepository;
    private final RankPromotionPolicy rankPolicy;

    public CapabilityV2ProfileService(UserCapabilityStateRepository stateRepository,
                                      CapabilityBucketRepository bucketRepository,
                                      RankPromotionPolicy rankPolicy) {
        this.stateRepository = stateRepository;
        this.bucketRepository = bucketRepository;
        this.rankPolicy = rankPolicy;
    }

    public CapabilityProfile profileForUser(String rawUserId) {
        String userId = rawUserId == null ? "" : rawUserId.trim();
        if (userId.isEmpty()) {
            return CapabilityProfile.empty();
        }

        List<UserCapabilityStateEntity> states = stateRepository
                .findByUserIdOrderByAbilityScoreDescUpdatedAtDesc(userId);
        if (states.isEmpty()) {
            return CapabilityProfile.empty();
        }

        Map<Long, CapabilityBucketEntity> bucketsById = bucketRepository.findAllById(
                        states.stream()
                                .map(UserCapabilityStateEntity::getBucketId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList()
                ).stream()
                .filter(bucket -> bucket.getStatus() == CapabilityBucketStatus.ACTIVE)
                .collect(Collectors.toMap(CapabilityBucketEntity::getId, Function.identity()));

        List<CapabilitySummary> summaries = states.stream()
                .map(state -> toSummary(state, bucketsById.get(state.getBucketId())))
                .filter(Objects::nonNull)
                .sorted(SUMMARY_ORDER)
                .toList();

        return new CapabilityProfile(
                summaries.stream()
                        .filter(summary -> summary.bucketType() == CapabilityBucketType.KNOWLEDGE)
                        .toList(),
                summaries.stream()
                        .filter(summary -> summary.bucketType() == CapabilityBucketType.FUNCTION)
                        .toList(),
                summaries.stream().mapToInt(CapabilitySummary::evidenceCount).sum(),
                latestUpdatedAt(summaries)
        );
    }

    private CapabilitySummary toSummary(UserCapabilityStateEntity state, CapabilityBucketEntity bucket) {
        if (bucket == null) {
            return null;
        }
        BigDecimal abilityScore = zeroIfNull(state.getAbilityScore());
        // Derived from the state row alone, so the list can show the reminder without reading evidence.
        boolean promotionPending = rankPolicy.promotionPending(state.getRankName(), abilityScore);
        return new CapabilitySummary(
                bucket.getId(),
                bucket.getBucketType(),
                bucket.getDisplayName(),
                bucket.getDescription(),
                bucket.getDomainName(),
                bucket.getGroupName(),
                zeroIfNull(state.getExperienceScore()),
                abilityScore,
                state.getAbilityUncertainty() == null ? BigDecimal.ONE : state.getAbilityUncertainty(),
                state.getRankName(),
                state.getEvidenceCount(),
                state.getUpdatedAt(),
                promotionPending,
                promotionPending ? rankPolicy.rankFor(abilityScore) : null
        );
    }

    private Instant latestUpdatedAt(Collection<CapabilitySummary> summaries) {
        return summaries.stream()
                .map(CapabilitySummary::updatedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record CapabilityProfile(
            List<CapabilitySummary> knowledgeBuckets,
            List<CapabilitySummary> functionBuckets,
            int totalEvidenceCount,
            Instant lastUpdatedAt
    ) {
        static CapabilityProfile empty() {
            return new CapabilityProfile(List.of(), List.of(), 0, null);
        }
    }

    public record CapabilitySummary(
            Long bucketId,
            CapabilityBucketType bucketType,
            String displayName,
            String description,
            String domainName,
            String groupName,
            BigDecimal experienceScore,
            BigDecimal abilityScore,
            BigDecimal abilityUncertainty,
            String rankName,
            int evidenceCount,
            Instant updatedAt,
            boolean promotionPending,
            String pendingRank
    ) {
    }
}
