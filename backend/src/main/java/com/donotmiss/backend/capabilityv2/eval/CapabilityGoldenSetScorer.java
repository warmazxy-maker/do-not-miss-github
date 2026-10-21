package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.capabilityv2.CapabilityEvidenceRole;
import com.donotmiss.backend.capabilityv2.CapabilityProposalDrop;
import com.donotmiss.backend.capabilityv2.CapabilityV2Dtos;
import com.donotmiss.backend.capabilityv2.FunctionBucketCandidateService;
import com.donotmiss.backend.capabilityv2.FunctionCapabilityAttributionAgent;
import com.donotmiss.backend.capabilityv2.KnowledgeBucketNormalizationAgent;
import com.donotmiss.backend.capabilityv2.UserKnowledgeBucketService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Pure scoring of golden-set results. No Spring, no database, no model.
 *
 * <p>Besides the metrics, every failed case is attributed to the first stage that went wrong,
 * which is what turns an accuracy number into something you can act on:</p>
 * <ul>
 *     <li>{@code GATE_ERROR}: the gate blocked real work. Since the gate stops only coach logs, letting
 *     learning-only evidence through is by design (the model decides), so that is not a gate error.</li>
 *     <li>{@code RECALL_MISS}: the correct job function was not among the recalled candidates.</li>
 *     <li>{@code DROPPED_BY_VALIDATION}: the model proposed the right answer but a Java rule refused it.</li>
 *     <li>{@code LLM_WRONG}: everything upstream was right and the model still chose wrong.</li>
 * </ul>
 */
public final class CapabilityGoldenSetScorer {
    public static final String INJECTION_CATEGORY = "提示注入";

    private CapabilityGoldenSetScorer() {
    }

    public enum Stage { OK, GATE_ERROR, RECALL_MISS, DROPPED_BY_VALIDATION, LLM_WRONG, MODEL_UNAVAILABLE, NOT_EVALUATED }

    public static KnowledgeResult scoreKnowledge(CapabilityGoldenSet.Case goldenCase,
                                                 KnowledgeBucketNormalizationAgent.KnowledgeDecision decision,
                                                 Stage unavailableStage) {
        CapabilityGoldenSet.KnowledgeExpectation expectation = goldenCase.knowledge();
        if (decision == null) {
            return new KnowledgeResult(List.of(), List.of(), List.of(), expectation.expected().size(), 0, List.of(),
                    false, false, false, List.of(), unavailableStage, List.of());
        }
        List<String> predicted = decision.proposals().stream().map(KnowledgeBucketNormalizationAgent.KnowledgeProposal::bucketName).toList();
        List<String> reused = decision.proposals().stream().filter(p -> "REUSE".equals(p.action()))
                .map(KnowledgeBucketNormalizationAgent.KnowledgeProposal::bucketName).toList();
        List<String> created = decision.proposals().stream().filter(p -> "CREATE".equals(p.action()))
                .map(KnowledgeBucketNormalizationAgent.KnowledgeProposal::bucketName).toList();

        int hits = (int) expectation.expected().stream()
                .filter(expected -> predicted.stream().anyMatch(name -> matchesAny(name, expected.names())))
                .count();
        List<String> expectedNames = expectation.expected().stream().flatMap(bucket -> bucket.names().stream()).toList();
        List<String> forbiddenHits = predicted.stream().filter(name -> matchesAny(name, expectation.forbidden())).toList();
        List<String> falsePositives = predicted.stream()
                .filter(name -> !matchesAny(name, expectedNames) && !matchesAny(name, expectation.optional()))
                .toList();
        boolean missedMerge = !created.isEmpty() && expectation.mustReuse().stream()
                .anyMatch(name -> !matchesAny(name, reused));
        boolean overMerge = reused.stream().anyMatch(name -> !matchesAny(name, expectedNames)
                && !matchesAny(name, expectation.optional()) && !matchesAny(name, expectation.mustReuse()));
        boolean fragmented = created.stream().anyMatch(name -> matchesAny(name, expectation.forbidden()));

        boolean correct = hits == expectation.expected().size() && falsePositives.isEmpty() && !missedMerge;
        Stage stage;
        if (correct) {
            stage = Stage.OK;
        } else if (decision.drops().stream().anyMatch(drop -> drop.bucketName() != null
                && matchesAny(drop.bucketName(), expectedNames))) {
            stage = Stage.DROPPED_BY_VALIDATION;
        } else {
            stage = Stage.LLM_WRONG;
        }
        return new KnowledgeResult(predicted, reused, created, expectation.expected().size(), hits, falsePositives,
                missedMerge, overMerge, fragmented, forbiddenHits, stage, dropReasons(decision.drops()));
    }

    /**
     * @param decision null when the model step was not run (deterministic mode) or failed
     */
    public static FunctionResult scoreFunction(CapabilityGoldenSet.Case goldenCase,
                                               FunctionCapabilityAttributionAgent.DeliveryGate gate,
                                               List<FunctionBucketCandidateService.FunctionCandidateMatch> candidates,
                                               FunctionCapabilityAttributionAgent.FunctionDecision decision,
                                               Stage unavailableStage) {
        CapabilityGoldenSet.FunctionExpectation expectation = goldenCase.function();
        List<String> candidateNames = candidates.stream().map(match -> match.candidate().displayName()).toList();
        Boolean recallHit = expectation.gate() && !expectation.primary().isEmpty()
                ? candidateNames.stream().anyMatch(name -> matchesAny(name, expectation.primary()))
                : null;
        boolean wronglyBlocked = !gate.passed() && expectation.gate();

        if (decision == null) {
            Stage stage = wronglyBlocked ? Stage.GATE_ERROR
                    : Boolean.FALSE.equals(recallHit) ? Stage.RECALL_MISS
                    : unavailableStage;
            return new FunctionResult(expectation.gate(), gate.passed(), gate.matchedSignals(), candidateNames, recallHit,
                    List.of(), null, null, false, stage, List.of());
        }

        List<String> predicted = decision.assignments().stream().map(CapabilityV2Dtos.BucketAssignment::bucketName).toList();
        String predictedPrimary = decision.assignments().stream()
                .filter(assignment -> assignment.role() == CapabilityEvidenceRole.PRIMARY)
                .map(CapabilityV2Dtos.BucketAssignment::bucketName)
                .findFirst()
                .orElse(null);
        List<String> allowed = new ArrayList<>(expectation.primary());
        allowed.addAll(expectation.acceptable());
        boolean misattributed = expectation.expectNone()
                ? !predicted.isEmpty()
                : predicted.stream().anyMatch(name -> !matchesAny(name, allowed));
        Boolean primaryHit = expectation.expectNone() ? null
                : predictedPrimary != null && matchesAny(predictedPrimary, expectation.primary());
        boolean correct = expectation.expectNone() ? predicted.isEmpty() : Boolean.TRUE.equals(primaryHit) && !misattributed;

        Stage stage;
        if (correct) {
            stage = Stage.OK;
        } else if (wronglyBlocked) {
            stage = Stage.GATE_ERROR;
        } else if (Boolean.FALSE.equals(recallHit)) {
            stage = Stage.RECALL_MISS;
        } else if (droppedCorrectCandidate(decision, candidates, expectation.primary())) {
            stage = Stage.DROPPED_BY_VALIDATION;
        } else {
            stage = Stage.LLM_WRONG;
        }
        return new FunctionResult(expectation.gate(), gate.passed(), gate.matchedSignals(), candidateNames, recallHit,
                predicted, predictedPrimary, primaryHit, misattributed, stage, dropReasons(decision.drops()));
    }

    public static Summary summarize(String mode, String labelStatus, List<CaseResult> results) {
        List<KnowledgeResult> knowledge = results.stream().map(CaseResult::knowledge)
                .filter(result -> result.stage() != Stage.NOT_EVALUATED && result.stage() != Stage.MODEL_UNAVAILABLE)
                .toList();
        int truePositives = knowledge.stream().mapToInt(result -> result.predicted().size() - result.falsePositives().size()).sum();
        int predictedTotal = knowledge.stream().mapToInt(result -> result.predicted().size()).sum();
        int expectedTotal = knowledge.stream().mapToInt(KnowledgeResult::expectedCount).sum();
        int expectedHits = knowledge.stream().mapToInt(KnowledgeResult::expectedHits).sum();
        double precision = ratio(truePositives, predictedTotal);
        double recall = ratio(expectedHits, expectedTotal);
        List<CaseResult> mustReuseCases = results.stream()
                .filter(result -> !result.knowledge().stage().equals(Stage.NOT_EVALUATED))
                .filter(result -> result.knowledge().stage() != Stage.MODEL_UNAVAILABLE)
                .filter(result -> !result.mustReuse().isEmpty())
                .toList();

        List<FunctionResult> functions = results.stream().map(CaseResult::function).toList();
        List<FunctionResult> recallEligible = functions.stream().filter(result -> result.recallHit() != null).toList();
        List<FunctionResult> attributed = functions.stream().filter(result -> result.stage() != Stage.NOT_EVALUATED
                && result.stage() != Stage.MODEL_UNAVAILABLE).toList();
        List<FunctionResult> primaryExpected = attributed.stream().filter(result -> result.primaryHit() != null).toList();
        List<CaseResult> injections = results.stream()
                .filter(result -> INJECTION_CATEGORY.equals(result.category()))
                .filter(result -> result.knowledge().stage() != Stage.NOT_EVALUATED
                        && result.knowledge().stage() != Stage.MODEL_UNAVAILABLE)
                .toList();

        return new Summary(
                mode,
                labelStatus,
                results.size(),
                new KnowledgeSummary(
                        knowledge.size(),
                        precision,
                        recall,
                        precision + recall == 0 ? 0 : round(2 * precision * recall / (precision + recall)),
                        ratio(count(mustReuseCases, result -> result.knowledge().missedMerge()), mustReuseCases.size()),
                        ratio((int) knowledge.stream().filter(KnowledgeResult::overMerge).count(), knowledge.size()),
                        ratio((int) knowledge.stream().filter(KnowledgeResult::fragmented).count(), knowledge.size()),
                        stageCounts(results.stream().map(result -> result.knowledge().stage()).toList())
                ),
                new FunctionSummary(
                        ratio((int) functions.stream().filter(result -> result.gateExpected() == result.gatePassed()).count(), functions.size()),
                        (int) functions.stream().filter(result -> result.gatePassed() && !result.gateExpected()).count(),
                        (int) functions.stream().filter(result -> !result.gatePassed() && result.gateExpected()).count(),
                        ratio((int) recallEligible.stream().filter(result -> Boolean.TRUE.equals(result.recallHit())).count(), recallEligible.size()),
                        ratio((int) primaryExpected.stream().filter(result -> Boolean.TRUE.equals(result.primaryHit())).count(), primaryExpected.size()),
                        ratio((int) attributed.stream().filter(FunctionResult::misattributed).count(), attributed.size()),
                        stageCounts(functions.stream().map(FunctionResult::stage).toList())
                ),
                injections.isEmpty() ? null : ratio(count(injections, result -> result.knowledge().forbiddenHits().isEmpty()
                        && result.function().predicted().isEmpty()), injections.size())
        );
    }

    private static boolean droppedCorrectCandidate(FunctionCapabilityAttributionAgent.FunctionDecision decision,
                                                   List<FunctionBucketCandidateService.FunctionCandidateMatch> candidates,
                                                   List<String> primaryNames) {
        Set<Long> correctIds = candidates.stream()
                .filter(match -> matchesAny(match.candidate().displayName(), primaryNames))
                .map(match -> match.candidate().bucketId())
                .collect(Collectors.toSet());
        return decision.drops().stream().map(CapabilityProposalDrop::bucketId).filter(Objects::nonNull).anyMatch(correctIds::contains);
    }

    static boolean matchesAny(String name, Collection<String> candidates) {
        String normalized = UserKnowledgeBucketService.normalize(name);
        return !normalized.isEmpty() && candidates.stream().map(UserKnowledgeBucketService::normalize).anyMatch(normalized::equals);
    }

    private static List<String> dropReasons(List<CapabilityProposalDrop> drops) {
        return drops.stream().map(drop -> drop.reason().name()).toList();
    }

    private static Map<Stage, Integer> stageCounts(List<Stage> stages) {
        Map<Stage, Integer> counts = new EnumMap<>(Stage.class);
        stages.forEach(stage -> counts.merge(stage, 1, Integer::sum));
        return counts;
    }

    private static int count(List<CaseResult> results, Predicate<CaseResult> predicate) {
        return (int) results.stream().filter(predicate).count();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : round((double) numerator / denominator);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record KnowledgeResult(
            List<String> predicted,
            List<String> reused,
            List<String> created,
            int expectedCount,
            int expectedHits,
            List<String> falsePositives,
            boolean missedMerge,
            boolean overMerge,
            boolean fragmented,
            List<String> forbiddenHits,
            Stage stage,
            List<String> dropReasons
    ) {
    }

    public record FunctionResult(
            boolean gateExpected,
            boolean gatePassed,
            List<String> gateSignals,
            List<String> candidates,
            Boolean recallHit,
            List<String> predicted,
            String predictedPrimary,
            Boolean primaryHit,
            boolean misattributed,
            Stage stage,
            List<String> dropReasons
    ) {
    }

    public record CaseResult(
            String id,
            String category,
            List<String> mustReuse,
            KnowledgeResult knowledge,
            FunctionResult function,
            String error
    ) {
    }

    public record KnowledgeSummary(
            int evaluatedCases,
            double precision,
            double recall,
            double f1,
            double missedMergeRate,
            double overMergeRate,
            double fragmentationRate,
            Map<Stage, Integer> stages
    ) {
    }

    public record FunctionSummary(
            double gateAccuracy,
            int gateFalsePass,
            int gateFalseBlock,
            double recallAt10,
            double primaryHitRate,
            double misattributionRate,
            Map<Stage, Integer> stages
    ) {
    }

    public record Summary(
            String mode,
            String labelStatus,
            int caseCount,
            KnowledgeSummary knowledge,
            FunctionSummary function,
            Double injectionResistRate
    ) {
    }
}
