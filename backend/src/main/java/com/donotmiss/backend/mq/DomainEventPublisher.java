package com.donotmiss.backend.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Service
public class DomainEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final DomainEventOutboxRepository outboxRepository;
    private final MqProperties properties;
    private final ObjectMapper objectMapper;

    public DomainEventPublisher(DomainEventOutboxRepository outboxRepository,
                                MqProperties properties,
                                ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void publishEventIndex(Long eventId, String action) {
        DomainEventMessages.EventIndexMessage message =
                new DomainEventMessages.EventIndexMessage(eventId, action, Instant.now());
        publish(properties.getRoutingKeys().getEventIndex(), message);
    }

    public void publishEventQualityAnalysis(Long eventId) {
        DomainEventMessages.EventQualityAnalysisMessage message =
                new DomainEventMessages.EventQualityAnalysisMessage(eventId, Instant.now());
        publish(properties.getRoutingKeys().getEventQuality(), message);
    }

    public void publishAbilityEvidenceAssessment(Long recordId, String userId) {
        DomainEventMessages.AbilityEvidenceAssessmentMessage message =
                new DomainEventMessages.AbilityEvidenceAssessmentMessage(recordId, userId, Instant.now());
        publish(properties.getRoutingKeys().getAbilityEvidence(), message);
    }

    public void publishCapabilityV2Scoring(Long recordId,
                                           String userId,
                                           Long assessmentId,
                                           BigDecimal evidenceQuality,
                                           BigDecimal novelty) {
        DomainEventMessages.CapabilityV2ScoringMessage message =
                new DomainEventMessages.CapabilityV2ScoringMessage(
                        recordId,
                        userId,
                        assessmentId,
                        evidenceQuality,
                        novelty,
                        Instant.now(),
                        0,
                        DomainEventMessages.CapabilityV2ScoringMessage.SOURCE_ACHIEVEMENT,
                        null
                );
        publish(properties.getRoutingKeys().getCapabilityV2Scoring(), message);
    }

    /**
     * Sends one scored resume-interview evidence into the same V2 queue. Called inside the
     * transaction that marks the evidence SCORED, so the outbox row and the V1 result commit together.
     */
    public void publishResumeCapabilityV2Scoring(Long resumeEvidenceId,
                                                 String userId,
                                                 Long assessmentId,
                                                 BigDecimal evidenceQuality,
                                                 BigDecimal novelty) {
        DomainEventMessages.CapabilityV2ScoringMessage message =
                new DomainEventMessages.CapabilityV2ScoringMessage(
                        null,
                        userId,
                        assessmentId,
                        evidenceQuality,
                        novelty,
                        Instant.now(),
                        0,
                        DomainEventMessages.CapabilityV2ScoringMessage.SOURCE_RESUME_INTERVIEW,
                        resumeEvidenceId
                );
        publish(properties.getRoutingKeys().getCapabilityV2Scoring(), message);
    }

    /**
     * Re-enqueues a V2 scoring message through the outbox, to be sent again after {@code delay}.
     * Used when the model is temporarily unavailable: the outbox already provides durable,
     * time-based redelivery, so no new queue arguments or dead-letter setup are needed.
     */
    public void deferCapabilityV2Scoring(DomainEventMessages.CapabilityV2ScoringMessage message, Duration delay) {
        // The delay is set before the single save, so the dispatcher's poll can never send it early.
        publish(properties.getRoutingKeys().getCapabilityV2Scoring(), message.deferredOnce(), Instant.now().plus(delay));
    }

    public void publishUserProfileRefresh(String userId, String reason) {
        DomainEventMessages.UserProfileRefreshMessage message =
                new DomainEventMessages.UserProfileRefreshMessage(userId, reason, Instant.now());
        publish(properties.getRoutingKeys().getUserProfile(), message);
    }

    private void publish(String routingKey, Object message) {
        publish(routingKey, message, null);
    }

    private void publish(String routingKey, Object message, Instant availableAt) {
        try {
            String payloadJson = objectMapper.writeValueAsString(message);
            DomainEventOutboxEntity outbox = DomainEventOutboxEntity.pending(
                    properties.getExchange(),
                    routingKey,
                    message,
                    payloadJson
            );
            if (availableAt != null) {
                outbox.setNextAttemptAt(availableAt);
            }
            outboxRepository.save(outbox);
            log.debug("Saved domain event to outbox. routingKey={}, payloadType={}",
                    routingKey, message.getClass().getName());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize domain event payload: " + message.getClass().getName(), ex);
        }
    }
}
