package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankPromotionPolicyTest {
    private final RankPromotionPolicy policy = new RankPromotionPolicy(new AbilityScoreCalculator());

    @Test
    void onlyPromotionsIntoDevelopingOrAboveAreGated() {
        assertFalse(policy.requiresReview("UNRATED", "FOUNDATION"));
        assertTrue(policy.requiresReview("FOUNDATION", "DEVELOPING"));
        assertTrue(policy.requiresReview("UNRATED", "DEVELOPING"));
        assertFalse(policy.requiresReview("DEVELOPING", "DEVELOPING"));
        assertFalse(policy.requiresReview("PROFICIENT", "DEVELOPING"), "never a review for a lower rank");
    }

    @Test
    void nextAppliedRankHoldsGatedPromotionsAndNeverDemotes() {
        assertEquals("FOUNDATION", policy.nextAppliedRank("UNRATED", "FOUNDATION"));
        assertEquals("FOUNDATION", policy.nextAppliedRank("FOUNDATION", "PROFICIENT"));
        assertEquals("PROFICIENT", policy.nextAppliedRank("PROFICIENT", "DEVELOPING"));
    }

    @Test
    void onlyTheEvidenceThatCrossesIntoAHoldStartsIt() {
        assertTrue(policy.startsHold("FOUNDATION", new BigDecimal("28"), new BigDecimal("33")));
        assertFalse(policy.startsHold("FOUNDATION", new BigDecimal("33"), new BigDecimal("36")),
                "already held: later evidence must not open the review again");
        assertFalse(policy.startsHold("FOUNDATION", new BigDecimal("12"), new BigDecimal("20")));
    }

    @Test
    void pendingIsDerivedFromStateAlone() {
        assertTrue(policy.promotionPending("FOUNDATION", new BigDecimal("55")));
        assertFalse(policy.promotionPending("PROFICIENT", new BigDecimal("55")));
    }
}
