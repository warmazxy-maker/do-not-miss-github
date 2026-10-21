package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * The single rank rule for V2 buckets, shared by scoring (write), the detail replay (read)
 * and the promotion review (unlock).
 *
 * <p>Scores always apply in full. Only a promotion into {@code DEVELOPING} or above is gated:
 * the bucket keeps its current rank and the promotion waits for a review. While a promotion
 * is waiting, further evidence keeps adding score but does not open another review, so one
 * held promotion never turns every later evidence into "review required".</p>
 */
@Component
public class RankPromotionPolicy {
    public static final List<String> RANK_ORDER = List.of(
            "UNRATED", "FOUNDATION", "DEVELOPING", "PROFICIENT", "ADVANCED", "EXPERT"
    );
    private static final int FIRST_GATED_RANK = RANK_ORDER.indexOf("DEVELOPING");

    private final AbilityScoreCalculator calculator;

    public RankPromotionPolicy(AbilityScoreCalculator calculator) {
        this.calculator = calculator;
    }

    public String rankFor(BigDecimal abilityScore) {
        return calculator.rankFor(abilityScore);
    }

    /** True when moving from {@code appliedRank} to {@code proposedRank} needs a review first. */
    public boolean requiresReview(String appliedRank, String proposedRank) {
        int proposed = index(proposedRank);
        return proposed > index(appliedRank) && proposed >= FIRST_GATED_RANK;
    }

    /** The rank a bucket holds after one piece of evidence. Never demotes. */
    public String nextAppliedRank(String appliedRank, String proposedRank) {
        if (requiresReview(appliedRank, proposedRank)) {
            return normalize(appliedRank);
        }
        return index(proposedRank) >= index(appliedRank) ? normalize(proposedRank) : normalize(appliedRank);
    }

    /** A bucket is waiting for promotion review when its score already earns a gated higher rank. */
    public boolean promotionPending(String appliedRank, BigDecimal abilityScore) {
        return requiresReview(appliedRank, rankFor(abilityScore));
    }

    /**
     * Evidence is marked {@code REVIEW_REQUIRED} only when it is the one that starts a hold;
     * evidence arriving while a hold is already open is an ordinary scored evidence.
     */
    public boolean startsHold(String appliedRank, BigDecimal oldAbilityScore, BigDecimal newAbilityScore) {
        return !promotionPending(appliedRank, oldAbilityScore) && promotionPending(appliedRank, newAbilityScore);
    }

    public int index(String rank) {
        return Math.max(RANK_ORDER.indexOf(normalize(rank)), 0);
    }

    private String normalize(String rank) {
        return rank == null || rank.isBlank() ? "UNRATED" : rank.trim().toUpperCase();
    }
}
