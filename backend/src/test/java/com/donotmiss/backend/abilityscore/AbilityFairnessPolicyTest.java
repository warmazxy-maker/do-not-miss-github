package com.donotmiss.backend.abilityscore;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityFairnessPolicyTest {
    private final AbilityFairnessPolicy policy = new AbilityFairnessPolicy();

    @Test
    void teamEvidenceWithoutPersonalDetailsUsesConservativeCaps() {
        AbilityFairnessPolicy.ContributionDecision decision = policy.adjustTeamContribution(
                "参加团队项目并共同完成系统开发",
                "参与开发",
                new BigDecimal("0.9500"),
                new BigDecimal("0.9000")
        );

        assertEquals(new BigDecimal("0.5500"), decision.personalContribution());
        assertEquals(new BigDecimal("0.4500"), decision.personalContributionConfidence());
        assertTrue(decision.capped());
        assertFalse(decision.reasons().isEmpty());
    }

    @Test
    void detailedTeamContributionStillRequiresIndependentEvidenceForFullScore() {
        AbilityFairnessPolicy.ContributionDecision decision = policy.adjustTeamContribution(
                "三人小组合作开发后端系统",
                "我负责预约模块、事务边界设计、接口测试和上线复盘",
                BigDecimal.ONE,
                BigDecimal.ONE
        );

        assertEquals(new BigDecimal("0.8500"), decision.personalContribution());
        assertEquals(new BigDecimal("0.7500"), decision.personalContributionConfidence());
        assertTrue(decision.capped());
    }

    @Test
    void individualEvidenceIsNotArtificiallyReduced() {
        AbilityFairnessPolicy.ContributionDecision decision = policy.adjustTeamContribution(
                "独立完成个人项目并公开源码",
                "独立完成全部设计与开发",
                new BigDecimal("0.9200"),
                new BigDecimal("0.8800")
        );

        assertEquals(new BigDecimal("0.9200"), decision.personalContribution());
        assertEquals(new BigDecimal("0.8800"), decision.personalContributionConfidence());
        assertFalse(decision.capped());
    }

    @Test
    void manyDimensionsShareOneFixedRelevanceBudget() {
        AbilityFairnessPolicy.DimensionBudget budget = policy.normalizeDimensionBudget(List.of(
                new BigDecimal("0.9000"),
                new BigDecimal("0.9000"),
                new BigDecimal("0.9000")
        ));

        assertTrue(budget.normalized());
        assertEquals(new BigDecimal("0.7407"), budget.multiplier());
        assertEquals(List.of(
                new BigDecimal("0.6667"),
                new BigDecimal("0.6667"),
                new BigDecimal("0.6667")
        ), budget.adjustedRelevance());
    }

    @Test
    void dimensionsWithinBudgetRemainUnchanged() {
        AbilityFairnessPolicy.DimensionBudget budget = policy.normalizeDimensionBudget(List.of(
                new BigDecimal("0.8000"),
                new BigDecimal("0.7000")
        ));

        assertFalse(budget.normalized());
        assertEquals(new BigDecimal("1.0000"), budget.multiplier());
        assertEquals(List.of(
                new BigDecimal("0.8000"),
                new BigDecimal("0.7000")
        ), budget.adjustedRelevance());
    }
}
