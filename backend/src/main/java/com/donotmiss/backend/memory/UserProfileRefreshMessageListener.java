package com.donotmiss.backend.memory;

import com.donotmiss.backend.mq.DomainEventMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class UserProfileRefreshMessageListener {
    private static final Logger log = LoggerFactory.getLogger(UserProfileRefreshMessageListener.class);

    private final UserMemoryService userMemoryService;

    public UserProfileRefreshMessageListener(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    @RabbitListener(queues = "${app.mq.queues.user-profile}")
    public void handleUserProfileRefresh(DomainEventMessages.UserProfileRefreshMessage message) {
        if (message == null || message.userId() == null || message.userId().isBlank()) {
            log.warn("Ignored invalid user profile refresh message: {}", message);
            return;
        }

        userMemoryService.refreshSnapshot(message.userId().trim());
        log.info("Refreshed user profile snapshot from async MQ message. userId={}, reason={}",
                message.userId(), message.reason());
    }
}
