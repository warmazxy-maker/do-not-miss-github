package com.donotmiss.backend.ops;

import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.mq.DomainEventOutboxEntity;
import com.donotmiss.backend.mq.DomainEventOutboxRepository;
import com.donotmiss.backend.mq.DomainEventOutboxStatus;
import com.donotmiss.backend.mq.MqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The two halves of asynchronous delivery: the Outbox table (written in the business transaction,
 * waiting to be published) and the RabbitMQ queues (published, waiting to be consumed).
 */
@Service
public class OpsMessagingService {
    private static final Logger log = LoggerFactory.getLogger(OpsMessagingService.class);

    private final OpsQueries queries;
    private final DomainEventOutboxRepository outboxRepository;
    private final MqProperties mqProperties;
    private final ObjectProvider<AmqpAdmin> amqpAdmin;
    private final ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistry;
    private final int maxAttempts;

    public OpsMessagingService(OpsQueries queries,
                               DomainEventOutboxRepository outboxRepository,
                               MqProperties mqProperties,
                               ObjectProvider<AmqpAdmin> amqpAdmin,
                               ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistry,
                               @Value("${app.mq.outbox.max-attempts:8}") int maxAttempts) {
        this.queries = queries;
        this.outboxRepository = outboxRepository;
        this.mqProperties = mqProperties;
        this.amqpAdmin = amqpAdmin;
        this.listenerRegistry = listenerRegistry;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public OpsDtos.Messaging messaging() {
        return new OpsDtos.Messaging(mqProperties.getExchange(), queues(), outboxStats());
    }

    public List<OpsDtos.QueueRow> queues() {
        MqProperties.Queues names = mqProperties.getQueues();
        MqProperties.RoutingKeys keys = mqProperties.getRoutingKeys();
        Map<String, String> queueToKey = new LinkedHashMap<>();
        queueToKey.put(names.getEventIndex(), keys.getEventIndex());
        queueToKey.put(names.getEventQuality(), keys.getEventQuality());
        queueToKey.put(names.getAbilityEvidence(), keys.getAbilityEvidence());
        queueToKey.put(names.getCapabilityV2Scoring(), keys.getCapabilityV2Scoring());
        queueToKey.put(names.getUserProfile(), keys.getUserProfile());

        AmqpAdmin admin = amqpAdmin.getIfAvailable();
        Map<String, Boolean> running = listenerStates();
        List<OpsDtos.QueueRow> rows = new ArrayList<>();
        queueToKey.forEach((queue, routingKey) -> {
            Boolean listenerRunning = running.get(queue);
            if (admin == null) {
                rows.add(new OpsDtos.QueueRow(queue, routingKey, null, null, listenerRunning, "AmqpAdmin 不可用"));
                return;
            }
            try {
                Properties properties = admin.getQueueProperties(queue);
                if (properties == null) {
                    rows.add(new OpsDtos.QueueRow(queue, routingKey, null, null, listenerRunning, "队列不存在"));
                    return;
                }
                Object messages = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                Object consumers = properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT);
                rows.add(new OpsDtos.QueueRow(queue, routingKey,
                        messages == null ? null : ((Number) messages).longValue(),
                        consumers == null ? null : ((Number) consumers).intValue(),
                        listenerRunning, null));
            } catch (RuntimeException ex) {
                rows.add(new OpsDtos.QueueRow(queue, routingKey, null, null, listenerRunning, OpsHealthService.describe(ex)));
            }
        });
        return rows;
    }

    /** Which queues have a listener container in this JVM, and whether it is running. */
    private Map<String, Boolean> listenerStates() {
        Map<String, Boolean> states = new LinkedHashMap<>();
        RabbitListenerEndpointRegistry registry = listenerRegistry.getIfAvailable();
        if (registry == null) {
            return states;
        }
        registry.getListenerContainers().forEach(container -> {
            if (container instanceof AbstractMessageListenerContainer listener) {
                Arrays.stream(listener.getQueueNames()).forEach(queue -> states.put(queue, listener.isRunning()));
            }
        });
        return states;
    }

    public OpsDtos.OutboxStats outboxStats() {
        Map<String, Map<String, Object>> byKey = new TreeMap<>();
        long pending = 0;
        long failed = 0;
        for (Object[] row : queries.outboxCounts()) {
            DomainEventOutboxStatus status = (DomainEventOutboxStatus) row[0];
            String routingKey = (String) row[1];
            long count = ((Number) row[2]).longValue();
            Map<String, Object> entry = byKey.computeIfAbsent(routingKey, key -> {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("routingKey", key);
                for (DomainEventOutboxStatus value : DomainEventOutboxStatus.values()) {
                    fresh.put(value.name(), 0L);
                }
                return fresh;
            });
            entry.put(status.name(), count);
            if (status == DomainEventOutboxStatus.PENDING) {
                pending += count;
            } else if (status == DomainEventOutboxStatus.FAILED) {
                failed += count;
            }
        }
        Instant now = Instant.now();
        return new OpsDtos.OutboxStats(
                maxAttempts,
                new ArrayList<>(byKey.values()),
                pending,
                queries.countDeferred(maxAttempts, now),
                failed,
                queries.countExhausted(maxAttempts));
    }

    /** {@code view}: ALL, PENDING, FAILED, SENT, or STUCK (deferred into the future or out of attempts). */
    public List<OpsDtos.OutboxRow> outbox(String view, String routingKey, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        String key = routingKey == null || routingKey.isBlank() ? null : routingKey.trim();
        Instant now = Instant.now();
        List<DomainEventOutboxEntity> events;
        if ("STUCK".equalsIgnoreCase(view)) {
            events = queries.stuckOutbox(maxAttempts, now, page);
        } else {
            DomainEventOutboxStatus status = view == null || view.isBlank() || "ALL".equalsIgnoreCase(view)
                    ? null : DomainEventOutboxStatus.valueOf(view.trim().toUpperCase());
            events = queries.outbox(status, key, page);
        }
        return events.stream().map(event -> toRow(event, now)).toList();
    }

    OpsDtos.OutboxRow toRow(DomainEventOutboxEntity event, Instant now) {
        boolean unsent = event.getStatus() != DomainEventOutboxStatus.SENT;
        boolean exhausted = unsent && event.getAttempts() >= maxAttempts;
        boolean deferred = unsent && !exhausted && event.getNextAttemptAt() != null && event.getNextAttemptAt().isAfter(now);
        return new OpsDtos.OutboxRow(
                event.getId(),
                event.getRoutingKey(),
                shortType(event.getPayloadType()),
                event.getStatus().name(),
                event.getAttempts(),
                event.getNextAttemptAt(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getSentAt(),
                event.getPayloadJson(),
                deferred,
                exhausted);
    }

    /**
     * Puts a message back into the dispatcher's next poll: status PENDING, attempts reset and due now.
     * A SENT message is refused, because re-publishing it would make consumers process it twice.
     */
    @Transactional
    public OpsDtos.OutboxRow retry(Long id, String operator) {
        DomainEventOutboxEntity event = outboxRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("消息不存在"));
        if (event.getStatus() == DomainEventOutboxStatus.SENT) {
            throw ApiException.badRequest("消息已投递成功，不能重复投递");
        }
        int previousAttempts = event.getAttempts();
        event.setStatus(DomainEventOutboxStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now());
        DomainEventOutboxEntity saved = outboxRepository.save(event);
        log.info("Outbox message manually re-queued. id={}, routingKey={}, previousAttempts={}, operator={}",
                id, event.getRoutingKey(), previousAttempts, operator);
        return toRow(saved, Instant.now());
    }

    int maxAttempts() {
        return maxAttempts;
    }

    static String shortType(String payloadType) {
        if (payloadType == null) {
            return null;
        }
        int dollar = payloadType.lastIndexOf('$');
        int dot = payloadType.lastIndexOf('.');
        return payloadType.substring(Math.max(dollar, dot) + 1);
    }
}
