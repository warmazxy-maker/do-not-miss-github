package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.abilityscore.AbilityAssessmentJobRepository;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentEntity;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentRepository;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Asynchronous orchestration for one completed activity or challenge, traced as one agent run.
 *
 * <p>Order of work: read inputs, run every model step with no transaction open, then commit all
 * writes at once through {@link CapabilityV2PipelineWriter}. If a model step has no usable answer
 * the run fails with {@link CapabilityModelUnavailableException} before anything is written, so the
 * listener can defer the message and the completion keeps its chance to be scored.</p>
 *
 * <p>Steps and artifacts of the {@code CAPABILITY_V2_PIPELINE} run:</p>
 * <ol>
 *     <li>EVIDENCE_SNAPSHOT: the normalized evidence and the user's existing knowledge buckets.</li>
 *     <li>KNOWLEDGE_NORMALIZATION: raw model answer, validated proposals, dropped proposals.</li>
 *     <li>FUNCTION_CANDIDATE_RECALL: candidates with raw scores and matched terms.</li>
 *     <li>FUNCTION_DELIVERY_GATE: pass/fail and the delivery words that decided it.</li>
 *     <li>FUNCTION_ATTRIBUTION: raw model answer, validated assignments, dropped proposals.</li>
 *     <li>CAPABILITY_V2_WRITE: final assignments and per-bucket score results.</li>
 * </ol>
 * <p>Trace writes use their own transactions and swallow their own failures, so tracing can
 * never break scoring.</p>
 */
@Service
public class CapabilityV2AsyncScoringService {
    private final CapabilityEvidenceInputService evidenceInputService;
    private final UserKnowledgeBucketService userKnowledgeBucketService;
    private final KnowledgeBucketNormalizationAgent knowledgeAgent;
    private final FunctionCapabilityAttributionAgent functionAgent;
    private final CapabilityV2ScoringService scoringService;
    private final CapabilityV2PipelineWriter writer;
    private final AbilityEvidenceAssessmentRepository assessmentRepository;
    private final AbilityAssessmentJobRepository jobRepository;
    private final AgentRunService agentRunService;
    private final AgentTraceArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public CapabilityV2AsyncScoringService(
            CapabilityEvidenceInputService evidenceInputService,
            UserKnowledgeBucketService userKnowledgeBucketService,
            KnowledgeBucketNormalizationAgent knowledgeAgent,
            FunctionCapabilityAttributionAgent functionAgent,
            CapabilityV2ScoringService scoringService,
            CapabilityV2PipelineWriter writer,
            AbilityEvidenceAssessmentRepository assessmentRepository,
            AbilityAssessmentJobRepository jobRepository,
            AgentRunService agentRunService,
            AgentTraceArtifactService artifactService,
            ObjectMapper objectMapper
    ) {
        this.evidenceInputService = evidenceInputService;
        this.userKnowledgeBucketService = userKnowledgeBucketService;
        this.knowledgeAgent = knowledgeAgent;
        this.functionAgent = functionAgent;
        this.scoringService = scoringService;
        this.writer = writer;
        this.assessmentRepository = assessmentRepository;
        this.jobRepository = jobRepository;
        this.agentRunService = agentRunService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    public ProcessingOutcome process(Long recordId,
                                     String userId,
                                     Long assessmentId,
                                     BigDecimal evidenceQuality,
                                     BigDecimal novelty) {
        requireMessage(recordId, userId, assessmentId);
        CapabilityEvidenceInput evidence = evidenceInputService.fromAchievement(recordId, userId.trim());
        AbilityEvidenceAssessmentEntity assessment = verifyAssessmentOwnership(assessmentId, recordId, evidence.userId());

        if (!evidence.scoringEligible()) {
            return ProcessingOutcome.skipped(evidence.evidenceId(), "证据不具备计分资格：" + evidence.eligibilityReason());
        }
        // Checked before any model call, so a redelivered message costs nothing.
        if (scoringService.hasRecordedBusinessEvidence(evidence)) {
            return ProcessingOutcome.skipped(evidence.evidenceId(), "重复投递：该完成记录已写入 V2 能力账本");
        }

        Trace trace = new Trace(agentRunService.startRun(
                evidence.userId(),
                AgentRunType.CAPABILITY_V2_PIPELINE,
                "双标签归因与评分：" + evidence.title(),
                "recordId=" + recordId + ", assessmentId=" + assessmentId + ", evidenceId=" + evidence.evidenceId()
        ));
        try {
            List<CapabilityV2Dtos.BucketCandidate> existing = trace.step(
                    AgentStepName.EVIDENCE_SNAPSHOT, "load evidence and personal knowledge buckets",
                    () -> userKnowledgeBucketService.loadForUser(evidence.userId()),
                    buckets -> {
                        trace.artifact(AgentStepName.EVIDENCE_SNAPSHOT, "capability.evidence",
                                CapabilityEvidenceSnapshots.of(evidence), evidence.title());
                        trace.artifact(AgentStepName.EVIDENCE_SNAPSHOT, "capability.knowledge.existing",
                                buckets, buckets.size() + " existing knowledge buckets");
                        return "existingKnowledge=" + buckets.size();
                    });

            KnowledgeBucketNormalizationAgent.KnowledgeDecision knowledgeDecision = trace.step(
                    AgentStepName.KNOWLEDGE_NORMALIZATION, "prompt=" + KnowledgeBucketNormalizationAgent.PROMPT_VERSION,
                    () -> knowledgeAgent.decide(evidence, existing),
                    decision -> {
                        trace.artifact(AgentStepName.KNOWLEDGE_NORMALIZATION, "capability.knowledge.raw_model_output",
                                decision.rawResponse(), decision.summary());
                        trace.artifact(AgentStepName.KNOWLEDGE_NORMALIZATION, "capability.knowledge.validated",
                                Map.of("proposals", decision.proposals(), "drops", decision.drops()),
                                decision.proposals().size() + " kept, " + decision.drops().size() + " dropped");
                        List<String> suspected = decision.proposals().stream()
                                .filter(proposal -> proposal.possibleDuplicateOf() != null)
                                .map(proposal -> proposal.bucketName() + "~" + proposal.possibleDuplicateOf())
                                .toList();
                        return "proposals=" + decision.proposals().size() + ", drops=" + dropReasons(decision.drops())
                                + (suspected.isEmpty() ? "" : ", possibleDuplicates=" + suspected);
                    });

            List<FunctionBucketCandidateService.FunctionCandidateMatch> candidates = trace.step(
                    AgentStepName.FUNCTION_CANDIDATE_RECALL, "top " + FunctionBucketCandidateService.DEFAULT_LIMIT,
                    () -> functionAgent.recall(evidence),
                    recalled -> {
                        boolean semantic = recalled.stream().anyMatch(FunctionBucketCandidateService.FunctionCandidateMatch::semantic);
                        trace.artifact(AgentStepName.FUNCTION_CANDIDATE_RECALL, "capability.function.candidates",
                                recalled.stream().map(this::candidateView).toList(),
                                recalled.size() + " candidates, " + (semantic ? "hybrid" : "keyword only"));
                        return "mode=" + (semantic ? "hybrid" : "keyword-only")
                                + ", candidates=" + recalled.stream().map(item -> item.candidate().displayName()).toList();
                    });

            FunctionCapabilityAttributionAgent.DeliveryGate gate = trace.step(
                    AgentStepName.FUNCTION_DELIVERY_GATE, "coach logs only; everything else goes to the model",
                    () -> functionAgent.deliveryGate(evidence),
                    result -> {
                        trace.artifact(AgentStepName.FUNCTION_DELIVERY_GATE, "capability.function.gate", result, result.reason());
                        return "passed=" + result.passed() + ", signals=" + result.matchedSignals();
                    });

            FunctionCapabilityAttributionAgent.FunctionDecision functionDecision = trace.step(
                    AgentStepName.FUNCTION_ATTRIBUTION, "prompt=" + FunctionCapabilityAttributionAgent.PROMPT_VERSION,
                    () -> functionAgent.decide(evidence, candidates, gate),
                    decision -> {
                        trace.artifact(AgentStepName.FUNCTION_ATTRIBUTION, "capability.function.raw_model_output",
                                decision.rawResponse(), decision.summary());
                        trace.artifact(AgentStepName.FUNCTION_ATTRIBUTION, "capability.function.validated",
                                Map.of("assignments", decision.assignments(), "drops", decision.drops()),
                                decision.assignments().size() + " kept, " + decision.drops().size() + " dropped");
                        return "mode=" + decision.modelName() + ", assignments=" + decision.assignments().size()
                                + ", drops=" + dropReasons(decision.drops());
                    });

            CapabilityV2PipelineWriter.WriteResult written = trace.step(
                    AgentStepName.CAPABILITY_V2_WRITE, "single transaction: buckets, audits, links, scores",
                    () -> writer.write(new CapabilityV2PipelineWriter.WriteCommand(
                            evidence,
                            assessment.getId(),
                            signalsFrom(assessment),
                            unit(evidenceQuality, new BigDecimal("0.5000")),
                            unit(novelty, BigDecimal.ONE),
                            existing,
                            knowledgeDecision,
                            candidates,
                            gate,
                            functionDecision,
                            trace.runId()
                    )),
                    result -> {
                        if (result.duplicate()) {
                            return "duplicate delivery, nothing written";
                        }
                        trace.artifact(AgentStepName.CAPABILITY_V2_WRITE, "capability.score.results",
                                result.scoring().bucketResults(), result.scoring().bucketResults().size() + " buckets scored");
                        return "scoredBuckets=" + result.scoring().bucketResults().size()
                                + ", skippedAssignments=" + result.scoring().skippedAssignments();
                    });

            if (written.duplicate()) {
                agentRunService.finishRun(trace.runId(), "duplicate delivery");
                return ProcessingOutcome.skipped(evidence.evidenceId(), "重复投递：该完成记录已写入 V2 能力账本");
            }
            agentRunService.finishRun(trace.runId(), "knowledge=" + written.knowledge().assignments().size()
                    + ", function=" + written.function().assignments().size()
                    + ", scored=" + written.scoring().bucketResults().size());
            return new ProcessingOutcome(
                    evidence.evidenceId(),
                    false,
                    "已完成知识标签归一、职能归因和 V2 Java 评分",
                    written.knowledge().assignments().size(),
                    written.function().assignments().size(),
                    written.scoring().bucketResults().size(),
                    written.scoring().skippedAssignments(),
                    trace.runId()
            );
        } catch (RuntimeException | Error exception) {
            agentRunService.failRun(trace.runId(), exception);
            throw exception;
        }
    }

    private AbilityEvidenceAssessmentEntity verifyAssessmentOwnership(Long assessmentId, Long recordId, String userId) {
        AbilityEvidenceAssessmentEntity assessment = assessmentRepository.findById(assessmentId).orElseThrow(
                () -> new IllegalArgumentException("能力证据评估不存在：" + assessmentId)
        );
        AbilityAssessmentJobEntity job = jobRepository.findById(assessment.getJobId()).orElseThrow(
                () -> new IllegalArgumentException("能力证据任务不存在：" + assessment.getJobId())
        );
        if (!recordId.equals(job.getAchievementRecordId()) || !userId.equals(job.getUserId())) {
            throw new IllegalArgumentException("能力证据评估与当前完成记录不匹配");
        }
        return assessment;
    }

    private Map<String, Object> candidateView(FunctionBucketCandidateService.FunctionCandidateMatch match) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("bucketId", match.candidate().bucketId());
        view.put("displayName", match.candidate().displayName());
        view.put("rawScore", match.rawScore());
        view.put("retrievalConfidence", match.retrievalConfidence());
        view.put("matchedTerms", match.matchedTerms());
        view.put("semanticSimilarity", match.semanticSimilarity());
        view.put("source", match.source());
        return view;
    }

    private List<String> dropReasons(List<CapabilityProposalDrop> drops) {
        return drops.stream().map(drop -> drop.reason().name()).toList();
    }

    private CapabilityV2ScoringService.ScoreSignals signalsFrom(AbilityEvidenceAssessmentEntity assessment) {
        return new CapabilityV2ScoringService.ScoreSignals(
                assessment.getActivityDifficulty(),
                assessment.getActivityDifficultyConfidence(),
                assessment.getCompletionQuality(),
                assessment.getCompletionQualityConfidence(),
                assessment.getPersonalContribution(),
                assessment.getPersonalContributionConfidence(),
                assessment.getAssessmentConfidence(),
                evidenceFindings(assessment.getEvidenceFindingsJson()),
                stringValues(assessment.getRiskFlagsJson(), 8, 160)
        );
    }

    private List<String> evidenceFindings(String json) {
        try {
            JsonNode root = objectMapper.readTree(json == null ? "[]" : json);
            if (!root.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : root) {
                String type = item.path("evidenceType").asText("").trim();
                String status = item.path("verificationStatus").asText("").trim();
                String quality = item.path("quality").asText("").trim();
                String value = String.join(" ", List.of(type, status, quality).stream()
                        .filter(text -> !text.isBlank())
                        .toList());
                if (!value.isBlank()) {
                    values.add(value);
                }
                if (values.size() >= 6) {
                    break;
                }
            }
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of("评估证据明细解析失败，按保守质量计分");
        }
    }

    private List<String> stringValues(String json, int limit, int maxLength) {
        try {
            JsonNode root = objectMapper.readTree(json == null ? "[]" : json);
            if (!root.isArray()) {
                return List.of();
            }
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonNode item : root) {
                String text = item.asText("").trim();
                if (!text.isBlank()) {
                    values.add(text.length() <= maxLength ? text : text.substring(0, maxLength));
                }
                if (values.size() >= limit) {
                    break;
                }
            }
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of("ASSESSMENT_JSON_PARSE_FAILED");
        }
    }

    private void requireMessage(Long recordId, String userId, Long assessmentId) {
        if (recordId == null || assessmentId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("V2 能力评分消息缺少 recordId、userId 或 assessmentId");
        }
    }

    private BigDecimal unit(BigDecimal value, BigDecimal fallback) {
        BigDecimal actual = value == null ? fallback : value;
        return actual.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** Small helper so every step is started, completed or failed the same way. */
    private final class Trace {
        private final Long runId;

        private Trace(Long runId) {
            this.runId = runId;
        }

        Long runId() {
            return runId;
        }

        <T> T step(AgentStepName name, String input, Supplier<T> work, java.util.function.Function<T, String> summarize) {
            Long stepId = agentRunService.startStep(runId, name, input);
            try {
                T result = work.get();
                agentRunService.completeStep(stepId, summarize.apply(result));
                return result;
            } catch (RuntimeException | Error exception) {
                agentRunService.failStep(stepId, exception);
                throw exception;
            }
        }

        void artifact(AgentStepName step, String type, Object content, String summary) {
            artifactService.record(runId, step, type, content, summary);
        }
    }

    public record ProcessingOutcome(
            String evidenceId,
            boolean skipped,
            String summary,
            int knowledgeAssignments,
            int functionAssignments,
            int scoredBuckets,
            int skippedAssignments,
            Long agentRunId
    ) {
        static ProcessingOutcome skipped(String evidenceId, String summary) {
            return new ProcessingOutcome(evidenceId, true, summary, 0, 0, 0, 0, null);
        }
    }
}
