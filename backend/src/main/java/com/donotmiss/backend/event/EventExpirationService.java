package com.donotmiss.backend.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The bridge between the two dimensions: time decides that an event has ended (derived, nothing
 * stored), and this job turns that into a publication change, APPROVED -> OFFLINE(ENDED), through
 * the state machine like every other change.
 * <p>
 * It is only a tidy-up. Students never depend on it: search, retrieval and the index all filter
 * on end time themselves, so an ended event is invisible the moment it ends, job or no job.
 * Before D2 every search ran this job inline (a write inside a read); that is gone.
 */
@Service
public class EventExpirationService {
    private static final Logger log = LoggerFactory.getLogger(EventExpirationService.class);

    private final EventRepository eventRepository;
    private final EventLifecycleService lifecycleService;
    private final TransactionTemplate transactionTemplate;

    public EventExpirationService(EventRepository eventRepository,
                                  EventLifecycleService lifecycleService,
                                  PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.lifecycleService = lifecycleService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            initialDelayString = "${app.events.expiration.initial-delay-ms:60000}",
            fixedDelayString = "${app.events.expiration.delay-ms:300000}"
    )
    public int endOverdueEvents() {
        List<Long> overdueIds = eventRepository
                .findByReviewStatusAndEndTimeLessThanEqual(EventReviewStatus.APPROVED, LocalDateTime.now())
                .stream()
                .map(EventEntity::getId)
                .toList();
        int ended = 0;
        for (Long eventId : overdueIds) {
            // One transaction per event: a conflict on one (someone edited it just now) must not
            // roll back the others. The next run picks it up again if it is still overdue.
            try {
                Boolean done = transactionTemplate.execute(status -> eventRepository.findById(eventId)
                        .filter(event -> event.getReviewStatus() == EventReviewStatus.APPROVED)
                        .map(event -> {
                            lifecycleService.transition(event, EventLifecycleAction.END, EventActor.SYSTEM, null);
                            return true;
                        })
                        .orElse(false));
                if (Boolean.TRUE.equals(done)) {
                    ended++;
                }
            } catch (RuntimeException ex) {
                log.warn("Could not end overdue event, will retry next run. eventId={}", eventId, ex);
            }
        }
        return ended;
    }
}
