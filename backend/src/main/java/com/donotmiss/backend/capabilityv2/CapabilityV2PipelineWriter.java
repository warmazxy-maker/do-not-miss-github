package com.donotmiss.backend.capabilityv2;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * The only write step of the V2 pipeline, in one short transaction that contains no model call.
 *
 * <p>New knowledge buckets, both classification audits, evidence links, score audits and user
 * states either all commit or all roll back. Before Stage 17 a bucket created by the knowledge
 * agent stayed behind when scoring failed later, and kept being offered to the model as an
 * "existing" label with no evidence behind it.</p>
 */
@Service
public class CapabilityV2PipelineWriter {
    private final KnowledgeBucketNormalizationAgent knowledgeAgent;
    private final FunctionCapabilityAttributionAgent functionAgent;
    private final CapabilityV2ScoringService scoringService;

    public CapabilityV2PipelineWriter(KnowledgeBucketNormalizationAgent knowledgeAgent,
                                      FunctionCapabilityAttributionAgent functionAgent,
                                      CapabilityV2ScoringService scoringService) {
        this.knowledgeAgent = knowledgeAgent;
        this.functionAgent = functionAgent;
        this.scoringService = scoringService;
    }

    @Transactional
    public WriteResult write(WriteCommand command) {
        // Re-checked inside the transaction: a duplicate delivery may have finished while this one waited for the model.
        if (scoringService.hasRecordedBusinessEvidence(command.evidence())) {
            return WriteResult.alreadyRecorded();
        }
        KnowledgeBucketNormalizationAgent.KnowledgeClassificationOutcome knowledge = knowledgeAgent.apply(
                command.evidence(), command.existingKnowledge(), command.knowledgeDecision(), command.agentRunId());
        FunctionCapabilityAttributionAgent.FunctionAttributionOutcome function = functionAgent.apply(
                command.evidence(), command.functionCandidates(), command.gate(), command.functionDecision(),
                command.agentRunId());
        CapabilityV2ScoringService.ScoringOutcome scoring = scoringService.score(new CapabilityV2ScoringService.ScoreRequest(
                command.evidence(),
                command.legacyAssessmentId(),
                command.signals(),
                command.evidenceQuality(),
                command.novelty(),
                knowledge.assignments(),
                function.assignments(),
                command.agentRunId()
        ));
        return new WriteResult(false, knowledge, function, scoring);
    }

    public record WriteCommand(
            CapabilityEvidenceInput evidence,
            Long legacyAssessmentId,
            CapabilityV2ScoringService.ScoreSignals signals,
            BigDecimal evidenceQuality,
            BigDecimal novelty,
            List<CapabilityV2Dtos.BucketCandidate> existingKnowledge,
            KnowledgeBucketNormalizationAgent.KnowledgeDecision knowledgeDecision,
            List<FunctionBucketCandidateService.FunctionCandidateMatch> functionCandidates,
            FunctionCapabilityAttributionAgent.DeliveryGate gate,
            FunctionCapabilityAttributionAgent.FunctionDecision functionDecision,
            Long agentRunId
    ) {
    }

    public record WriteResult(
            boolean duplicate,
            KnowledgeBucketNormalizationAgent.KnowledgeClassificationOutcome knowledge,
            FunctionCapabilityAttributionAgent.FunctionAttributionOutcome function,
            CapabilityV2ScoringService.ScoringOutcome scoring
    ) {
        static WriteResult alreadyRecorded() {
            return new WriteResult(true, null, null, null);
        }
    }
}
