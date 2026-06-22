package com.donotmiss.backend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class DomainEventOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DomainEventOutboxDispatcher.class);
    private static final List<DomainEventOutboxStatus> DISPATCHABLE_STATUSES =
            List.of(DomainEventOutboxStatus.PENDING, DomainEventOutboxStatus.FAILED);

    private final DomainEventOutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final long initialBackoffSeconds;
    private final long maxBackoffSeconds;

    public DomainEventOutboxDispatcher(DomainEventOutboxRepository outboxRepository,
                                       RabbitTemplate rabbitTemplate,
                                       ObjectMapper objectMapper,
                                       @Value("${app.mq.outbox.enabled:true}") boolean enabled,
                                       @Value("${app.mq.outbox.batch-size:20}") int batchSize,
                                       @Value("${app.mq.outbox.max-attempts:8}") int maxAttempts,
                                       @Value("${app.mq.outbox.initial-backoff-seconds:5}") long initialBackoffSeconds,
                                       @Value("${app.mq.outbox.max-backoff-seconds:300}") long maxBackoffSeconds) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffSeconds = Math.max(1, initialBackoffSeconds);
        this.maxBackoffSeconds = Math.max(this.initialBackoffSeconds, maxBackoffSeconds);
    }

    @Scheduled(
            initialDelayString = "${app.mq.outbox.initial-delay-ms:5000}",
            fixedDelayString = "${app.mq.outbox.delay-ms:5000}"
    )
    public void dispatchDueEvents() {
        if (!enabled) {
            return;
        }

        List<DomainEventOutboxEntity> events = outboxRepository.findPublishable(
                DISPATCHABLE_STATUSES,
                Instant.now(),
                maxAttempts,
                PageRequest.of(0, batchSize)
        );
        for (DomainEventOutboxEntity event : events) {
            dispatchOne(event.getId());
        }
    }

    @Transactional
    public void dispatchOne(Long eventId) {
        DomainEventOutboxEntity event = outboxRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() == DomainEventOutboxStatus.SENT || event.getAttempts() >= maxAttempts) {
            return;
        }

        try {
            Object payload = deserializePayload(event);
            rabbitTemplate.convertAndSend(event.getExchangeName(), event.getRoutingKey(), payload);
            event.setStatus(DomainEventOutboxStatus.SENT);
            event.setSentAt(Instant.now());
            event.setLastError(null);
            outboxRepository.save(event);
            log.info("Published outbox domain event. id={}, routingKey={}, payloadType={}",
                    event.getId(), event.getRoutingKey(), event.getPayloadType());
        } catch (Exception ex) {
            markFailed(event, ex);
        }
    }

    private Object deserializePayload(DomainEventOutboxEntity event) throws Exception {
        Class<?> payloadClass = Class.forName(event.getPayloadType());
        return objectMapper.readValue(event.getPayloadJson(), payloadClass);
    }

    private void markFailed(DomainEventOutboxEntity event, Exception ex) {
        int nextAttempts = event.getAttempts() + 1;
        event.setAttempts(nextAttempts);
        event.setStatus(DomainEventOutboxStatus.FAILED);
        event.setLastError(compact(errorMessage(ex), 1000));
        event.setNextAttemptAt(Instant.now().plus(retryBackoff(nextAttempts)));
        outboxRepository.save(event);
        log.warn("Failed to publish outbox domain event. id={}, attempts={}/{}, routingKey={}, error={}",
                event.getId(), nextAttempts, maxAttempts, event.getRoutingKey(), ex.getMessage());
    }

    private Duration retryBackoff(int attempts) {
        int power = Math.min(Math.max(attempts - 1, 0), 10);
        long seconds = initialBackoffSeconds * (1L << power);
        return Duration.ofSeconds(Math.min(seconds, maxBackoffSeconds));
    }

    private String errorMessage(Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (ex instanceof AmqpException) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private String compact(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
