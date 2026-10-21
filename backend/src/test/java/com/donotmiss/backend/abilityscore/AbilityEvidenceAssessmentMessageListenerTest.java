package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbilityEvidenceAssessmentMessageListenerTest {
    @Test
    void publishesV2ScoringOnlyAfterAnAssessmentExists() {
        EvidenceAssessmentAgentService assessmentService = mock(EvidenceAssessmentAgentService.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        when(assessmentService.assessRecord(9L, "student-1")).thenReturn(new EvidenceAssessmentDtos.AssessmentResponse(
                1L, 44L, "request", "student-1", 9L, "COMPLETED", "CLEAR", null,
                "mock", "a".repeat(64), new BigDecimal("0.83"), new BigDecimal("0.92"),
                new AbilityScoringExecution(null, "request", 0, 0, List.of())
        ));

        new AbilityEvidenceAssessmentMessageListener(assessmentService, publisher).handle(
                new DomainEventMessages.AbilityEvidenceAssessmentMessage(9L, "student-1", Instant.now())
        );

        verify(publisher).publishCapabilityV2Scoring(
                9L, "student-1", 44L, new BigDecimal("0.83"), new BigDecimal("0.92")
        );
    }

    @Test
    void doesNotPublishV2ForDuplicateAssessmentBlockedBeforeAssessmentCreation() {
        EvidenceAssessmentAgentService assessmentService = mock(EvidenceAssessmentAgentService.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        when(assessmentService.assessRecord(any(), any())).thenReturn(new EvidenceAssessmentDtos.AssessmentResponse(
                1L, null, "request", "student-1", 9L, "REVIEW_REQUIRED", "DUPLICATE_REVIEW", 8L,
                "duplicate-blocked", "a".repeat(64), BigDecimal.ZERO, BigDecimal.ZERO,
                new AbilityScoringExecution(null, "request", 0, 0, List.of())
        ));

        new AbilityEvidenceAssessmentMessageListener(assessmentService, publisher).handle(
                new DomainEventMessages.AbilityEvidenceAssessmentMessage(9L, "student-1", Instant.now())
        );

        verify(publisher, org.mockito.Mockito.never()).publishCapabilityV2Scoring(
                any(), any(), any(), any(), any()
        );
    }
}
