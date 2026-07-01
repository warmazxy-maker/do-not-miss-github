package com.donotmiss.backend.abilityscore;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityScoreCalculatorTest {
    private final AbilityScoreCalculator calculator = new AbilityScoreCalculator();

    @Test
    void sameInputAlwaysProducesSameOutput() {
        AbilityScoringModels.ScoreInput input = input(
                "58", "0.50", "65",
                "0.80", "0.90", "0.90",
                "0.90", "0.80", "0.85", "1.00",
                AbilityEvidenceSourceType.PUBLIC_CODE_REPOSITORY
        );

        AbilityScoringModels.ScoreOutput first = calculator.calculate(input);
        AbilityScoringModels.ScoreOutput second = calculator.calculate(input);

        assertEquals(first, second);
        assertEquals(AbilityScoringPolicyV2.RULE_VERSION, first.scoringRuleVersion());
    }

    @Test
    void highLevelUserGetsVeryLittleFromFarTooEasyTask() {
        AbilityScoringModels.ScoreOutput output = calculator.calculate(input(
                "80", "0.70", "20",
                "0.95", "0.95", "0.95",
                "0.90", "0.90", "0.90", "0.40",
                AbilityEvidenceSourceType.COMPANY_INTERNSHIP_OR_WORK
        ));

        assertEquals(new BigDecimal("0.0500"), output.difficultyMatchMultiplier());
        assertTrue(output.verifiedExperienceGain().compareTo(new BigDecimal("0.6000")) < 0);
        assertTrue(output.abilityGain().compareTo(new BigDecimal("0.0500")) < 0);
    }

    @Test
    void strongPersonalProjectCanBeatOrdinaryInternship() {
        AbilityScoringModels.ScoreOutput personalProject = calculator.calculate(input(
                "40", "0.50", "60",
                "0.95", "0.95", "0.95",
                "0.95", "0.95", "0.95", "1.00",
                AbilityEvidenceSourceType.RUNNABLE_PERSONAL_DEMO
        ));
        AbilityScoringModels.ScoreOutput ordinaryInternship = calculator.calculate(input(
                "40", "0.50", "55",
                "0.60", "0.45", "0.60",
                "0.80", "0.60", "0.80", "0.60",
                AbilityEvidenceSourceType.COMPANY_INTERNSHIP_OR_WORK
        ));

        assertTrue(
                AbilityEvidenceSourceType.COMPANY_INTERNSHIP_OR_WORK.credibilityPrior()
                        .compareTo(AbilityEvidenceSourceType.RUNNABLE_PERSONAL_DEMO.credibilityPrior()) > 0
        );
        assertTrue(personalProject.verifiedExperienceGain()
                .compareTo(ordinaryInternship.verifiedExperienceGain()) > 0);
    }

    @Test
    void profileConfidenceOnlyHasNarrowInfluence() {
        AbilityScoringModels.ScoreInput lowConfidence = input(
                "45", "0.10", "55",
                "0.85", "0.85", "0.90",
                "0.90", "0.85", "0.90", "0.90",
                AbilityEvidenceSourceType.PUBLIC_CODE_REPOSITORY
        );
        AbilityScoringModels.ScoreInput highConfidence = input(
                "45", "0.90", "55",
                "0.85", "0.85", "0.90",
                "0.90", "0.85", "0.90", "0.90",
                AbilityEvidenceSourceType.PUBLIC_CODE_REPOSITORY
        );

        AbilityScoringModels.ScoreOutput low = calculator.calculate(lowConfidence);
        AbilityScoringModels.ScoreOutput high = calculator.calculate(highConfidence);
        BigDecimal ratio = high.verifiedExperienceGain()
                .divide(low.verifiedExperienceGain(), 4, java.math.RoundingMode.HALF_UP);

        assertEquals(new BigDecimal("0.8500"), low.profileConfidenceMultiplier());
        assertEquals(new BigDecimal("1.1000"), high.profileConfidenceMultiplier());
        assertTrue(ratio.compareTo(new BigDecimal("1.3000")) < 0);
    }

    @Test
    void repeatedLowNoveltyActivityIsReducedAndFlagged() {
        AbilityScoringModels.ScoreOutput novel = calculator.calculate(input(
                "35", "0.50", "45",
                "0.85", "0.80", "0.85",
                "0.90", "0.80", "0.90", "1.00",
                AbilityEvidenceSourceType.SCHOOL_COURSE_OR_ASSIGNMENT
        ));
        AbilityScoringModels.ScoreOutput repeated = calculator.calculate(input(
                "35", "0.50", "45",
                "0.85", "0.80", "0.85",
                "0.90", "0.80", "0.90", "0.05",
                AbilityEvidenceSourceType.SCHOOL_COURSE_OR_ASSIGNMENT
        ));

        assertTrue(repeated.verifiedExperienceGain().compareTo(novel.verifiedExperienceGain()) < 0);
        assertTrue(repeated.judgeFlags().contains("POSSIBLE_SCORE_FARMING"));
    }

    @Test
    void suspiciousDifficultyGapTriggersJudgeReview() {
        AbilityScoringModels.ScoreOutput output = calculator.calculate(input(
                "20", "0.30", "85",
                "0.90", "0.95", "0.90",
                "0.90", "0.35", "0.80", "1.00",
                AbilityEvidenceSourceType.SELF_REPORT
        ));

        assertTrue(output.requiresJudgeReview());
        assertTrue(output.judgeFlags().contains("DIFFICULTY_GAP_OVER_40"));
        assertTrue(output.judgeFlags().contains("HIGH_DIFFICULTY_WITH_WEAK_EVIDENCE"));
        assertTrue(output.judgeFlags().contains("STRONG_CLAIM_WITH_LOW_SOURCE_VERIFIABILITY"));
        assertTrue(output.judgeFlags().contains("LOW_CONFIDENCE_WITH_STRONG_CLAIM"));
    }

    @Test
    void uncertaintyFallsWithStrongIndependentEvidence() {
        AbilityScoringModels.ScoreOutput output = calculator.calculate(new AbilityScoringModels.ScoreInput(
                new BigDecimal("50"),
                new BigDecimal("30"),
                new BigDecimal("0.8000"),
                "DEVELOPING",
                new BigDecimal("0.5000"),
                new BigDecimal("40"),
                new BigDecimal("0.9000"),
                new BigDecimal("0.9000"),
                new BigDecimal("0.9500"),
                new BigDecimal("0.9000"),
                new BigDecimal("0.9000"),
                new BigDecimal("0.9500"),
                new BigDecimal("1.0000"),
                AbilityEvidenceSourceType.VERIFIED_THIRD_PARTY_RECORD
        ));

        assertTrue(output.newAbilityUncertainty().compareTo(output.oldAbilityUncertainty()) < 0);
        assertFalse(output.newAbilityUncertainty().compareTo(AbilityScoringPolicyV2.MIN_ABILITY_UNCERTAINTY) < 0);
    }

    private AbilityScoringModels.ScoreInput input(String currentAbility,
                                                  String profileConfidence,
                                                  String activityDifficulty,
                                                  String completionQuality,
                                                  String personalContribution,
                                                  String contributionConfidence,
                                                  String relevance,
                                                  String evidenceQuality,
                                                  String assessmentConfidence,
                                                  String novelty,
                                                  AbilityEvidenceSourceType sourceType) {
        BigDecimal ability = new BigDecimal(currentAbility);
        return new AbilityScoringModels.ScoreInput(
                new BigDecimal("100.0000"),
                ability,
                new BigDecimal("0.6000"),
                calculator.rankFor(ability),
                new BigDecimal(profileConfidence),
                new BigDecimal(activityDifficulty),
                new BigDecimal(completionQuality),
                new BigDecimal(personalContribution),
                new BigDecimal(contributionConfidence),
                new BigDecimal(relevance),
                new BigDecimal(evidenceQuality),
                new BigDecimal(assessmentConfidence),
                new BigDecimal(novelty),
                sourceType
        );
    }
}
