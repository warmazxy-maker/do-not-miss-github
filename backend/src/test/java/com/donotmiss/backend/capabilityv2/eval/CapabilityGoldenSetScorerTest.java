package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.capabilityv2.CapabilityBucketType;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceRole;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceSourceKind;
import com.donotmiss.backend.capabilityv2.CapabilityProposalDrop;
import com.donotmiss.backend.capabilityv2.CapabilityV2Dtos;
import com.donotmiss.backend.capabilityv2.FunctionBucketCandidateService;
import com.donotmiss.backend.capabilityv2.FunctionCapabilityAttributionAgent;
import com.donotmiss.backend.capabilityv2.KnowledgeBucketNormalizationAgent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityGoldenSetScorerTest {
    private static final CapabilityGoldenSetScorer.Stage NOT_RUN = CapabilityGoldenSetScorer.Stage.NOT_EVALUATED;

    @Test
    void creatingANewBucketWhileAnExistingOneShouldBeReusedIsAMissedMerge() {
        CapabilityGoldenSet.Case goldenCase = knowledgeCase(List.of(new CapabilityGoldenSet.ExpectedBucket("Java", null)),
                List.of("Java"), List.of("Java基础"));
        KnowledgeBucketNormalizationAgent.KnowledgeDecision decision = knowledgeDecision(
                proposal("CREATE", null, "Java基础"));

        CapabilityGoldenSetScorer.KnowledgeResult result = CapabilityGoldenSetScorer.scoreKnowledge(goldenCase, decision, NOT_RUN);

        assertTrue(result.missedMerge());
        assertTrue(result.fragmented());
        assertEquals(List.of("Java基础"), result.forbiddenHits());
        assertEquals(CapabilityGoldenSetScorer.Stage.LLM_WRONG, result.stage());
    }

    @Test
    void reusingAnUnrelatedExistingBucketIsAnOverMerge() {
        CapabilityGoldenSet.Case goldenCase = knowledgeCase(List.of(new CapabilityGoldenSet.ExpectedBucket("Docker", List.of("容器"))),
                List.of(), List.of());
        CapabilityGoldenSetScorer.KnowledgeResult result = CapabilityGoldenSetScorer.scoreKnowledge(goldenCase,
                knowledgeDecision(proposal("REUSE", -1L, "Linux")), NOT_RUN);

        assertTrue(result.overMerge());
        assertEquals(0, result.expectedHits());
    }

    @Test
    void alternativesCountAsTheExpectedBucket() {
        CapabilityGoldenSet.Case goldenCase = knowledgeCase(List.of(new CapabilityGoldenSet.ExpectedBucket("Docker", List.of("容器"))),
                List.of(), List.of());
        CapabilityGoldenSetScorer.KnowledgeResult result = CapabilityGoldenSetScorer.scoreKnowledge(goldenCase,
                knowledgeDecision(proposal("CREATE", null, "容器")), NOT_RUN);

        assertEquals(CapabilityGoldenSetScorer.Stage.OK, result.stage());
    }

    @Test
    void aMissingCorrectCandidateIsBlamedOnRecallNotOnTheModel() {
        CapabilityGoldenSet.Case goldenCase = functionCase(true, List.of("后端开发"), false);
        List<FunctionBucketCandidateService.FunctionCandidateMatch> candidates = List.of(match(5L, "婚礼与庆典策划"));
        FunctionCapabilityAttributionAgent.FunctionDecision decision = functionDecision(
                List.of(assignment(5L, "婚礼与庆典策划")), List.of());

        CapabilityGoldenSetScorer.FunctionResult result = CapabilityGoldenSetScorer.scoreFunction(goldenCase,
                new FunctionCapabilityAttributionAgent.DeliveryGate(true, List.of("负责"), "ok"), candidates, decision, NOT_RUN);

        assertEquals(Boolean.FALSE, result.recallHit());
        assertTrue(result.misattributed());
        assertEquals(CapabilityGoldenSetScorer.Stage.RECALL_MISS, result.stage());
    }

    @Test
    void aCorrectAnswerRemovedByValidationIsReportedAsSuch() {
        CapabilityGoldenSet.Case goldenCase = functionCase(true, List.of("后端开发"), false);
        FunctionCapabilityAttributionAgent.FunctionDecision decision = functionDecision(List.of(),
                List.of(new CapabilityProposalDrop(0, "SELECT", 7L, null, CapabilityProposalDrop.Reason.MISSING_RELEVANCE)));

        CapabilityGoldenSetScorer.FunctionResult result = CapabilityGoldenSetScorer.scoreFunction(goldenCase,
                new FunctionCapabilityAttributionAgent.DeliveryGate(true, List.of(), "ok"), List.of(match(7L, "后端开发")), decision, NOT_RUN);

        assertEquals(CapabilityGoldenSetScorer.Stage.DROPPED_BY_VALIDATION, result.stage());
    }

    @Test
    void aBlockedGateOnRealWorkIsAGateError() {
        CapabilityGoldenSet.Case goldenCase = functionCase(true, List.of("翻译"), false);

        CapabilityGoldenSetScorer.FunctionResult result = CapabilityGoldenSetScorer.scoreFunction(goldenCase,
                new FunctionCapabilityAttributionAgent.DeliveryGate(false, List.of(), "blocked"), List.of(match(1L, "翻译")),
                functionDecision(List.of(), List.of()), NOT_RUN);

        assertEquals(CapabilityGoldenSetScorer.Stage.GATE_ERROR, result.stage());
        assertFalse(result.misattributed());
    }

    private CapabilityGoldenSet.Case knowledgeCase(List<CapabilityGoldenSet.ExpectedBucket> expected,
                                                   List<String> mustReuse, List<String> forbidden) {
        return new CapabilityGoldenSet.Case("T", "test", CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION, "t", "d", "r",
                List.of(), List.of(),
                new CapabilityGoldenSet.KnowledgeExpectation(expected, List.of(), mustReuse, forbidden),
                new CapabilityGoldenSet.FunctionExpectation(false, List.of(), List.of(), true));
    }

    private CapabilityGoldenSet.Case functionCase(boolean gate, List<String> primary, boolean expectNone) {
        return new CapabilityGoldenSet.Case("T", "test", CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION, "t", "d", "r",
                List.of(), List.of(),
                new CapabilityGoldenSet.KnowledgeExpectation(List.of(), List.of(), List.of(), List.of()),
                new CapabilityGoldenSet.FunctionExpectation(gate, primary, List.of(), expectNone));
    }

    private KnowledgeBucketNormalizationAgent.KnowledgeDecision knowledgeDecision(
            KnowledgeBucketNormalizationAgent.KnowledgeProposal... proposals) {
        return new KnowledgeBucketNormalizationAgent.KnowledgeDecision("p", "m", "s", null, List.of(proposals), List.of());
    }

    private KnowledgeBucketNormalizationAgent.KnowledgeProposal proposal(String action, Long id, String name) {
        return new KnowledgeBucketNormalizationAgent.KnowledgeProposal(action, id, name, null, null, List.of(),
                BigDecimal.ONE, BigDecimal.ONE, List.of(), "");
    }

    private FunctionCapabilityAttributionAgent.FunctionDecision functionDecision(List<CapabilityV2Dtos.BucketAssignment> assignments,
                                                                                 List<CapabilityProposalDrop> drops) {
        return new FunctionCapabilityAttributionAgent.FunctionDecision("m", "s", null, assignments, drops);
    }

    private CapabilityV2Dtos.BucketAssignment assignment(Long id, String name) {
        return new CapabilityV2Dtos.BucketAssignment(id, CapabilityBucketType.FUNCTION, name, BigDecimal.ONE, BigDecimal.ONE,
                CapabilityEvidenceRole.PRIMARY, "", List.of(), List.of());
    }

    private FunctionBucketCandidateService.FunctionCandidateMatch match(Long id, String name) {
        return new FunctionBucketCandidateService.FunctionCandidateMatch(new CapabilityV2Dtos.BucketCandidate(
                id, CapabilityBucketType.FUNCTION, name, null, null, null, List.of()), BigDecimal.ONE, 10, List.of());
    }
}
