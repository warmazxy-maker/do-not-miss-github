package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityV2ScoringMessageListenerTest {
    @Mock
    private CapabilityV2AsyncScoringService asyncScoringService;
    @Mock
    private DomainEventPublisher publisher;

    @Test
    void modelOutageDefersTheMessageInsteadOfLosingIt() {
        DomainEventMessages.CapabilityV2ScoringMessage message = message(2);
        when(asyncScoringService.process(any(), any(), any(), any(), any()))
                .thenThrow(CapabilityModelUnavailableException.noResponse("KNOWLEDGE_NORMALIZATION"));

        listener().handle(message);

        verify(publisher).deferCapabilityV2Scoring(message, Duration.ofMinutes(4));
    }

    @Test
    void givesUpAfterTheLastDeferralOrWhenTheModelIsSwitchedOff() {
        doThrow(CapabilityModelUnavailableException.noResponse("FUNCTION_ATTRIBUTION"))
                .doThrow(CapabilityModelUnavailableException.disabled("FUNCTION_ATTRIBUTION"))
                .when(asyncScoringService).process(any(), any(), any(), any(), any());

        listener().handle(message(CapabilityV2ScoringMessageListener.MAX_DEFERRALS));
        listener().handle(message(0));

        verifyNoInteractions(publisher);
    }

    @Test
    void otherFailuresStillUseTheBrokerRetry() {
        when(asyncScoringService.process(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("optimistic lock"));

        assertThrows(IllegalStateException.class, () -> listener().handle(message(0)));
        verifyNoInteractions(publisher);
    }

    @Test
    void backoffDoublesAndIsCappedAtOneHour() {
        assertEquals(Duration.ofMinutes(1), CapabilityV2ScoringMessageListener.deferralDelay(0));
        assertEquals(Duration.ofMinutes(32), CapabilityV2ScoringMessageListener.deferralDelay(5));
        assertEquals(Duration.ofMinutes(60), CapabilityV2ScoringMessageListener.deferralDelay(7));
    }

    @Test
    void messagesWrittenBeforeTheDeferralFieldExistedStartAtZero() {
        DomainEventMessages.CapabilityV2ScoringMessage legacy = new DomainEventMessages.CapabilityV2ScoringMessage(
                1L, "u", 2L, BigDecimal.ONE, BigDecimal.ONE, Instant.now(), null, null, null);
        assertEquals(0, legacy.deferralCount());
        assertEquals(1, legacy.deferredOnce().deferralCount());
    }

    @Test
    void oldOutboxPayloadsWithoutASourceStillMeanAGrowthRecord() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DomainEventMessages.CapabilityV2ScoringMessage legacy = mapper.readValue(
                "{\"recordId\":99,\"userId\":\"student-1\",\"assessmentId\":71,\"evidenceQuality\":0.8,"
                        + "\"novelty\":1,\"occurredAt\":\"2026-08-14T00:00:00Z\",\"deferralCount\":1}",
                DomainEventMessages.CapabilityV2ScoringMessage.class);

        assertEquals(DomainEventMessages.CapabilityV2ScoringMessage.SOURCE_ACHIEVEMENT, legacy.evidenceSource());
        assertFalse(legacy.fromResumeInterview());
        assertFalse(mapper.writeValueAsString(resumeMessage(0)).contains("fromResumeInterview"));
    }

    @Test
    void resumeMessagesGoToTheResumeEntryPoint() {
        when(asyncScoringService.processResumeEvidence(any(), any(), any(), any(), any()))
                .thenReturn(new CapabilityV2AsyncScoringService.ProcessingOutcome("resume-interview-evidence:31",
                        false, "ok", 1, 1, 2, 0, 500L));

        listener().handle(resumeMessage(0));

        verify(asyncScoringService).processResumeEvidence(31L, "student-1", 71L, new BigDecimal("0.7"), BigDecimal.ONE);
        verify(asyncScoringService, never()).process(any(), any(), any(), any(), any());
    }

    @Test
    void aDeferredResumeMessageKeepsItsSource() {
        DomainEventMessages.CapabilityV2ScoringMessage message = resumeMessage(0);
        when(asyncScoringService.processResumeEvidence(any(), any(), any(), any(), any()))
                .thenThrow(CapabilityModelUnavailableException.noResponse("FUNCTION_ATTRIBUTION"));

        listener().handle(message);

        verify(publisher).deferCapabilityV2Scoring(message, Duration.ofMinutes(1));
        DomainEventMessages.CapabilityV2ScoringMessage deferred = message.deferredOnce();
        assertTrue(deferred.fromResumeInterview());
        assertEquals(31L, deferred.resumeEvidenceId());
    }

    @Test
    void aResumeMessageWithoutItsEvidenceIdIsIgnored() {
        listener().handle(new DomainEventMessages.CapabilityV2ScoringMessage(
                99L, "student-1", 71L, BigDecimal.ONE, BigDecimal.ONE, Instant.now(), 0,
                DomainEventMessages.CapabilityV2ScoringMessage.SOURCE_RESUME_INTERVIEW, null));

        verifyNoInteractions(asyncScoringService, publisher);
    }

    private CapabilityV2ScoringMessageListener listener() {
        return new CapabilityV2ScoringMessageListener(asyncScoringService, publisher);
    }

    private DomainEventMessages.CapabilityV2ScoringMessage message(int deferrals) {
        return new DomainEventMessages.CapabilityV2ScoringMessage(
                99L, "student-1", 71L, new BigDecimal("0.8"), BigDecimal.ONE, Instant.now(), deferrals, null, null);
    }

    private DomainEventMessages.CapabilityV2ScoringMessage resumeMessage(int deferrals) {
        return new DomainEventMessages.CapabilityV2ScoringMessage(
                null, "student-1", 71L, new BigDecimal("0.7"), BigDecimal.ONE, Instant.now(), deferrals,
                DomainEventMessages.CapabilityV2ScoringMessage.SOURCE_RESUME_INTERVIEW, 31L);
    }
}
