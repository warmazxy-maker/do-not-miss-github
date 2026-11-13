package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.mq.DomainEventMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventSearchIndexMessageListener {
    private static final Logger log = LoggerFactory.getLogger(EventSearchIndexMessageListener.class);

    private final EventSearchIndexService eventSearchIndexService;

    public EventSearchIndexMessageListener(EventSearchIndexService eventSearchIndexService) {
        this.eventSearchIndexService = eventSearchIndexService;
    }

    @RabbitListener(queues = "${app.mq.queues.event-index}")
    @Transactional(readOnly = true)
    public void handleEventIndexMessage(DomainEventMessages.EventIndexMessage message) {
        if (message == null || message.eventId() == null || message.action() == null) {
            log.warn("Ignored invalid event index message: {}", message);
            return;
        }

        if (!eventSearchIndexService.isEnabled()) {
            // Used to return silently, so a backend started without SEARCH_ENABLED swallowed every
            // index message and the index fell behind unnoticed (PROBLEM_LOG 075).
            log.warn("Search is disabled in this backend (SEARCH_ENABLED is not true): event {} was NOT {}. "
                    + "The search index is now out of date; it is rebuilt at the next start with search enabled.",
                    message.eventId(), message.action());
            return;
        }

        if (DomainEventMessages.EVENT_INDEX_UPSERT.equalsIgnoreCase(message.action())) {
            eventSearchIndexService.indexByIdOrThrow(message.eventId());
            log.info("Indexed event {} from async MQ message.", message.eventId());
            return;
        }

        if (DomainEventMessages.EVENT_INDEX_DELETE.equalsIgnoreCase(message.action())) {
            eventSearchIndexService.deleteOrThrow(message.eventId());
            log.info("Deleted event {} from async MQ message.", message.eventId());
            return;
        }

        log.warn("Ignored event index message with unknown action: {}", message);
    }
}
