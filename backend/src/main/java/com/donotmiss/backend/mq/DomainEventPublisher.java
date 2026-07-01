package com.donotmiss.backend.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public void publishGrowthTagExtraction(Long recordId, String userId, String sourceType) {
        DomainEventMessages.GrowthTagExtractionMessage message =
                new DomainEventMessages.GrowthTagExtractionMessage(recordId, userId, sourceType, Instant.now());
        publish(properties.getRoutingKeys().getGrowthTag(), message);
    }

    public void publishAbilityEvidenceAssessment(Long recordId, String userId) {
        DomainEventMessages.AbilityEvidenceAssessmentMessage message =
                new DomainEventMessages.AbilityEvidenceAssessmentMessage(recordId, userId, Instant.now());
        publish(properties.getRoutingKeys().getAbilityEvidence(), message);
    }

    public void publishUserProfileRefresh(String userId, String reason) {
        DomainEventMessages.UserProfileRefreshMessage message =
                new DomainEventMessages.UserProfileRefreshMessage(userId, reason, Instant.now());
        publish(properties.getRoutingKeys().getUserProfile(), message);
    }

    private void publish(String routingKey, Object message) {
        try {
            String payloadJson = objectMapper.writeValueAsString(message);
            DomainEventOutboxEntity outbox = DomainEventOutboxEntity.pending(
                    properties.getExchange(),
                    routingKey,
                    message,
                    payloadJson
            );
            outboxRepository.save(outbox);
            log.debug("Saved domain event to outbox. routingKey={}, payloadType={}",
                    routingKey, message.getClass().getName());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize domain event payload: " + message.getClass().getName(), ex);
        }
    }
}
