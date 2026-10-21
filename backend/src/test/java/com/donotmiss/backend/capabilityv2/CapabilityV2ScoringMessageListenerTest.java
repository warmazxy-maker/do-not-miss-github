package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
                1L, "u", 2L, BigDecimal.ONE, BigDecimal.ONE, Instant.now(), null);
        assertEquals(0, legacy.deferralCount());
        assertEquals(1, legacy.deferredOnce().deferralCount());
    }

    private CapabilityV2ScoringMessageListener listener() {
        return new CapabilityV2ScoringMessageListener(asyncScoringService, publisher);
    }

    private DomainEventMessages.CapabilityV2ScoringMessage message(int deferrals) {
        return new DomainEventMessages.CapabilityV2ScoringMessage(
                99L, "student-1", 71L, new BigDecimal("0.8"), BigDecimal.ONE, Instant.now(), deferrals);
    }
}
