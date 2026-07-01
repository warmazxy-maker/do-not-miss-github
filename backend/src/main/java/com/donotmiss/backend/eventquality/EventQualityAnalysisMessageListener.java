package com.donotmiss.backend.eventquality;

import com.donotmiss.backend.mq.DomainEventMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class EventQualityAnalysisMessageListener {
    private static final Logger log = LoggerFactory.getLogger(EventQualityAnalysisMessageListener.class);

    private final EventQualityService eventQualityService;

    public EventQualityAnalysisMessageListener(EventQualityService eventQualityService) {
        this.eventQualityService = eventQualityService;
    }

    @RabbitListener(queues = "${app.mq.queues.event-quality}")
    public void handleEventQualityMessage(DomainEventMessages.EventQualityAnalysisMessage message) {
        if (message == null || message.eventId() == null) {
            log.warn("Ignored invalid event quality message: {}", message);
            return;
        }

        boolean analyzed = eventQualityService.analyzeById(message.eventId());
        if (analyzed) {
            log.info("Analyzed event quality from async MQ message. eventId={}", message.eventId());
        } else {
            log.warn("Skipped event quality analysis because event does not exist. eventId={}", message.eventId());
        }
    }
}
