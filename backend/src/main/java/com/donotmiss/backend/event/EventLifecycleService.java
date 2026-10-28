package com.donotmiss.backend.event;

import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The only way to change an event's publication state (D2). Every caller, whether the organiser,
 * an operator, the AI review or the expiry job, goes through {@link #transition}, which
 * <ol>
 *   <li>checks the transition table and guards ({@link EventLifecycleRules}),</li>
 *   <li>updates the state and the fields that belong to it (offline reason, revision note, route),</li>
 *   <li>runs the side effects bound to entering or leaving a state: search index, AI review message,</li>
 *   <li>appends one history row.</li>
 * </ol>
 * All of it joins the caller's transaction; messages go through the outbox, so they are sent
 * only if the state change commits.
 */
@Service
public class EventLifecycleService {
    static final String ENDED_REASON = "活动已结束";
    static final String DISCARDED_REASON = "发布者放弃了这个活动";

    private final EventRepository eventRepository;
    private final EventStatusHistoryRepository historyRepository;
    private final DomainEventPublisher domainEventPublisher;

    public EventLifecycleService(EventRepository eventRepository,
                                 EventStatusHistoryRepository historyRepository,
                                 DomainEventPublisher domainEventPublisher) {
        this.eventRepository = eventRepository;
        this.historyRepository = historyRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    /** First history row of a new event (it starts as DRAFT; nothing else happens). */
    @Transactional
    public void recordCreated(EventEntity event, EventActor actor) {
        historyRepository.save(EventStatusHistoryEntity.of(
                event.getId(), null, event.getReviewStatus(), "CREATE", actor, null));
    }

    @Transactional
    public EventEntity transition(EventEntity event, EventLifecycleAction action, EventActor actor, String reason) {
        LocalDateTime now = LocalDateTime.now();
        EventReviewStatus from = event.getReviewStatus();
        EventReviewStatus to = EventLifecycleRules.check(event, action, actor, reason, now);
        String note = reason == null || reason.isBlank() ? null : reason.trim();

        if (from == EventReviewStatus.OFFLINE && to != EventReviewStatus.OFFLINE) {
            event.setOfflineReasonCode(null);
            event.setOfflineReason(null);
        }
        switch (to) {
            case OFFLINE -> {
                EventOfflineReason code = offlineReasonOf(action, actor);
                event.setOfflineReasonCode(code);
                event.setOfflineReason(switch (code) {
                    case ENDED -> ENDED_REASON;
                    case DISCARDED -> DISCARDED_REASON;
                    default -> note;
                });
                note = event.getOfflineReason();
            }
            case NEEDS_REVISION -> {
                event.setRevisionNote(note);
                if (action == EventLifecycleAction.AI_REQUEST_REVISION) {
                    event.setAiRevisionCount(event.getAiRevisionCount() + 1);
                }
            }
            case PENDING_REVIEW -> event.setReviewRoute(EventLifecycleRules.routeFor(event, action, actor));
            case APPROVED -> {
                // A fresh approval starts a fresh count: the next edit is reviewed by the AI again.
                event.setRevisionNote(null);
                event.setAiRevisionCount(0);
                event.setReviewRoute(EventReviewRoute.AI);
            }
            default -> {
            }
        }
        event.setReviewStatus(to);
        EventEntity saved = eventRepository.save(event);

        runSideEffects(saved, from, to, now);
        historyRepository.save(EventStatusHistoryEntity.of(saved.getId(), from, to, action.name(), actor, note));
        return saved;
    }

    private void runSideEffects(EventEntity event, EventReviewStatus from, EventReviewStatus to, LocalDateTime now) {
        if (from == EventReviewStatus.APPROVED && to != EventReviewStatus.APPROVED) {
            domainEventPublisher.publishEventIndex(event.getId(), DomainEventMessages.EVENT_INDEX_DELETE);
        }
        if (to == EventReviewStatus.APPROVED && !event.hasEnded(now)) {
            domainEventPublisher.publishEventIndex(event.getId(), DomainEventMessages.EVENT_INDEX_UPSERT);
        }
        if (to == EventReviewStatus.PENDING_REVIEW && event.getReviewRoute() == EventReviewRoute.AI) {
            domainEventPublisher.publishEventQualityAnalysis(event.getId());
        }
    }

    private EventOfflineReason offlineReasonOf(EventLifecycleAction action, EventActor actor) {
        return switch (action) {
            case END -> EventOfflineReason.ENDED;
            case DISCARD -> EventOfflineReason.DISCARDED;
            default -> actor.type() == EventActorType.OPERATOR ? EventOfflineReason.OPERATOR : EventOfflineReason.ORGANIZER;
        };
    }
}
