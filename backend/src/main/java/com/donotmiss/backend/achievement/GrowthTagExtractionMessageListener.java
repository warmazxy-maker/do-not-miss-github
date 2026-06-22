package com.donotmiss.backend.achievement;

import com.donotmiss.backend.mq.DomainEventMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class GrowthTagExtractionMessageListener {
    private static final Logger log = LoggerFactory.getLogger(GrowthTagExtractionMessageListener.class);

    private final GrowthTagService growthTagService;

    public GrowthTagExtractionMessageListener(GrowthTagService growthTagService) {
        this.growthTagService = growthTagService;
    }

    @RabbitListener(queues = "${app.mq.queues.growth-tag}")
    public void handleGrowthTagExtraction(DomainEventMessages.GrowthTagExtractionMessage message) {
        if (message == null || message.recordId() == null) {
            log.warn("Ignored invalid growth tag extraction message: {}", message);
            return;
        }

        boolean extracted = growthTagService.upsertFromRecordId(message.recordId(), message.userId());
        if (extracted) {
            log.info("Extracted growth tags from async MQ message. recordId={}, sourceType={}",
                    message.recordId(), message.sourceType());
        } else {
            log.warn("Ignored growth tag extraction message because record was not found. recordId={}, userId={}",
                    message.recordId(), message.userId());
        }
    }
}
