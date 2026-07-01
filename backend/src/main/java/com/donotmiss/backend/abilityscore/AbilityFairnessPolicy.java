package com.donotmiss.backend.abilityscore;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AbilityFairnessPolicy {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TEAM_WITHOUT_DETAIL_MAX_CONTRIBUTION = new BigDecimal("0.5500");
    private static final BigDecimal TEAM_WITH_DETAIL_MAX_CONTRIBUTION = new BigDecimal("0.8500");
    private static final BigDecimal TEAM_WITHOUT_DETAIL_MAX_CONFIDENCE = new BigDecimal("0.4500");
    private static final BigDecimal TEAM_WITH_DETAIL_MAX_CONFIDENCE = new BigDecimal("0.7500");
    private static final BigDecimal TOTAL_DIMENSION_RELEVANCE_BUDGET = new BigDecimal("2.0000");

    public ContributionDecision adjustTeamContribution(String activityText,
                                                       String did,
                                                       BigDecimal claimedContribution,
                                                       BigDecimal claimedConfidence) {
        BigDecimal contribution = unit(claimedContribution);
        BigDecimal confidence = unit(claimedConfidence);
        boolean teamContext = containsAny(
                activityText,
                "团队", "小组", "协作", "合作", "组员", "共同", "team", "group"
        );
        if (!teamContext) {
            return new ContributionDecision(contribution, confidence, false, List.of());
        }

        boolean contributionDescribed = did != null && did.trim().length() >= 12;
        BigDecimal contributionCap = contributionDescribed
                ? TEAM_WITH_DETAIL_MAX_CONTRIBUTION
                : TEAM_WITHOUT_DETAIL_MAX_CONTRIBUTION;
        BigDecimal confidenceCap = contributionDescribed
                ? TEAM_WITH_DETAIL_MAX_CONFIDENCE
                : TEAM_WITHOUT_DETAIL_MAX_CONFIDENCE;
        BigDecimal adjustedContribution = contribution.min(contributionCap);
        BigDecimal adjustedConfidence = confidence.min(confidenceCap);

        List<String> reasons = new ArrayList<>();
        if (!contributionDescribed) {
            reasons.add("团队活动缺少明确的个人职责描述，个人贡献和判断置信度采用保守上限");
        } else if (adjustedContribution.compareTo(contribution) < 0
                || adjustedConfidence.compareTo(confidence) < 0) {
            reasons.add("团队活动即使描述了个人贡献，也需要独立证据才能获得接近满分的贡献值");
        }
        return new ContributionDecision(
                adjustedContribution,
                adjustedConfidence,
                adjustedContribution.compareTo(contribution) < 0
                        || adjustedConfidence.compareTo(confidence) < 0,
                List.copyOf(reasons)
        );
    }

    public DimensionBudget normalizeDimensionBudget(List<BigDecimal> relevanceValues) {
        if (relevanceValues == null || relevanceValues.isEmpty()) {
            return new DimensionBudget(ONE.setScale(4), List.of(), false);
        }
        BigDecimal total = relevanceValues.stream()
                .map(this::unit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal multiplier = total.compareTo(TOTAL_DIMENSION_RELEVANCE_BUDGET) <= 0
                ? ONE
                : TOTAL_DIMENSION_RELEVANCE_BUDGET.divide(total, 6, RoundingMode.HALF_UP);
        List<BigDecimal> adjusted = relevanceValues.stream()
                .map(this::unit)
                .map(value -> value.multiply(multiplier).setScale(4, RoundingMode.HALF_UP))
                .toList();
        return new DimensionBudget(
                multiplier.setScale(4, RoundingMode.HALF_UP),
                adjusted,
                multiplier.compareTo(ONE) < 0
        );
    }

    private boolean containsAny(String text, String... keywords) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal unit(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP);
    }

    public record ContributionDecision(
            BigDecimal personalContribution,
            BigDecimal personalContributionConfidence,
            boolean capped,
            List<String> reasons
    ) {
    }

    public record DimensionBudget(
            BigDecimal multiplier,
            List<BigDecimal> adjustedRelevance,
            boolean normalized
    ) {
    }
}
