package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.mq.DomainEventMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class AbilityEvidenceAssessmentMessageListener {
    private static final Logger log = LoggerFactory.getLogger(AbilityEvidenceAssessmentMessageListener.class);

    private final EvidenceAssessmentAgentService assessmentAgentService;

    public AbilityEvidenceAssessmentMessageListener(EvidenceAssessmentAgentService assessmentAgentService) {
        this.assessmentAgentService = assessmentAgentService;
    }

    @RabbitListener(queues = "${app.mq.queues.ability-evidence}")
    public void handle(DomainEventMessages.AbilityEvidenceAssessmentMessage message) {
        if (message == null || message.recordId() == null) {
            log.warn("Ignored invalid ability evidence assessment message: {}", message);
            return;
        }
        EvidenceAssessmentDtos.AssessmentResponse result =
                assessmentAgentService.assessRecord(message.recordId(), message.userId());
        log.info(
                "Completed ability evidence assessment. recordId={}, assessmentId={}, dimensions={}, mode={}",
                message.recordId(),
                result.assessmentId(),
                result.scoring().scoredDimensions(),
                result.mode()
        );
    }
}
