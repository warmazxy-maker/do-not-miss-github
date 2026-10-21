package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityV2PipelineWriterTest {
    @Mock private KnowledgeBucketNormalizationAgent knowledgeAgent;
    @Mock private FunctionCapabilityAttributionAgent functionAgent;
    @Mock private CapabilityV2ScoringService scoringService;

    @Test
    void aDeliveryThatFinishedWhileThisOneWaitedForTheModelWritesNothing() {
        CapabilityV2PipelineWriter.WriteCommand command = command();
        when(scoringService.hasRecordedBusinessEvidence(command.evidence())).thenReturn(true);

        assertTrue(writer().write(command).duplicate());
        verifyNoInteractions(knowledgeAgent, functionAgent);
    }

    @Test
    void scoresTheAppliedAssignmentsUnderTheSameRun() {
        CapabilityV2PipelineWriter.WriteCommand command = command();
        when(scoringService.hasRecordedBusinessEvidence(command.evidence())).thenReturn(false);
        when(knowledgeAgent.apply(command.evidence(), List.of(), command.knowledgeDecision(), 9L))
                .thenReturn(new KnowledgeBucketNormalizationAgent.KnowledgeClassificationOutcome("k", List.of(), List.of(), "m", "s", List.of()));
        when(functionAgent.apply(command.evidence(), List.of(), command.gate(), command.functionDecision(), 9L))
                .thenReturn(new FunctionCapabilityAttributionAgent.FunctionAttributionOutcome("f", List.of(), List.of(), "m", "s", List.of()));
        when(scoringService.score(any())).thenReturn(new CapabilityV2ScoringService.ScoringOutcome("e", 0, List.of()));

        writer().write(command);

        ArgumentCaptor<CapabilityV2ScoringService.ScoreRequest> request = ArgumentCaptor.forClass(CapabilityV2ScoringService.ScoreRequest.class);
        verify(scoringService).score(request.capture());
        assertEquals(9L, request.getValue().agentRunId());
    }

    private CapabilityV2PipelineWriter writer() {
        return new CapabilityV2PipelineWriter(knowledgeAgent, functionAgent, scoringService);
    }

    private CapabilityV2PipelineWriter.WriteCommand command() {
        CapabilityEvidenceInput evidence = new CapabilityEvidenceInput(
                "capability-evidence-v2", "achievement-record:1", "key", CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION,
                "student-1", "1", AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.name(),
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.credibilityPrior(),
                "t", "d", "r", "{}", "{}", List.of(), Instant.now(), true, "ok", "a".repeat(64));
        return new CapabilityV2PipelineWriter.WriteCommand(
                evidence, 1L,
                new CapabilityV2ScoringService.ScoreSignals(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, List.of(), List.of()),
                BigDecimal.ONE, BigDecimal.ONE, List.of(),
                new KnowledgeBucketNormalizationAgent.KnowledgeDecision("p", "m", "s", null, List.of(), List.of()),
                List.of(),
                new FunctionCapabilityAttributionAgent.DeliveryGate(true, List.of(), "ok"),
                new FunctionCapabilityAttributionAgent.FunctionDecision("m", "s", null, List.of(), List.of()),
                9L);
    }
}
