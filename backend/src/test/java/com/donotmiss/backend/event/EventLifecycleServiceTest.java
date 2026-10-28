package com.donotmiss.backend.event;

import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Side effects bound to states: what happens on entering / leaving them, and the history row. */
class EventLifecycleServiceTest {
    private EventRepository eventRepository;
    private EventStatusHistoryRepository historyRepository;
    private DomainEventPublisher publisher;
    private EventLifecycleService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        historyRepository = mock(EventStatusHistoryRepository.class);
        publisher = mock(DomainEventPublisher.class);
        when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new EventLifecycleService(eventRepository, historyRepository, publisher);
    }

    private static EventEntity event(EventReviewStatus status) {
        EventEntity event = EventEntity.detached(7L);
        event.setCreatedByUserId("org-1");
        event.setStartTime(LocalDateTime.now().plusDays(1));
        event.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        event.setReviewStatus(status);
        return event;
    }

    private EventStatusHistoryEntity lastHistory() {
        ArgumentCaptor<EventStatusHistoryEntity> captor = ArgumentCaptor.forClass(EventStatusHistoryEntity.class);
        verify(historyRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void submitSendsToAiReviewAndRecordsHistory() {
        EventEntity event = event(EventReviewStatus.DRAFT);
        service.transition(event, EventLifecycleAction.SUBMIT, EventActor.organizer("org-1"), null);

        assertEquals(EventReviewStatus.PENDING_REVIEW, event.getReviewStatus());
        verify(publisher).publishEventQualityAnalysis(7L);
        verify(publisher, never()).publishEventIndex(anyLong(), any());
        EventStatusHistoryEntity row = lastHistory();
        assertEquals(EventReviewStatus.DRAFT, row.getFromStatus());
        assertEquals(EventReviewStatus.PENDING_REVIEW, row.getToStatus());
        assertEquals(EventActorType.ORGANIZER, row.getActorType());
    }

    @Test
    void manualRouteDoesNotCallTheModel() {
        EventEntity event = event(EventReviewStatus.NEEDS_REVISION);
        event.setAiRevisionCount(EventLifecycleRules.AI_REVISION_LIMIT);
        service.transition(event, EventLifecycleAction.SUBMIT, EventActor.organizer("org-1"), null);

        assertEquals(EventReviewRoute.MANUAL, event.getReviewRoute());
        verify(publisher, never()).publishEventQualityAnalysis(anyLong());
    }

    @Test
    void aiRevisionCountsAndKeepsTheNote() {
        EventEntity event = event(EventReviewStatus.PENDING_REVIEW);
        service.transition(event, EventLifecycleAction.AI_REQUEST_REVISION, EventActor.AI, "缺少报名方式");

        assertEquals(EventReviewStatus.NEEDS_REVISION, event.getReviewStatus());
        assertEquals(1, event.getAiRevisionCount());
        assertEquals("缺少报名方式", event.getRevisionNote());
    }

    @Test
    void approvalIndexesAndResetsTheCount() {
        EventEntity event = event(EventReviewStatus.PENDING_REVIEW);
        event.setAiRevisionCount(3);
        event.setReviewRoute(EventReviewRoute.MANUAL);
        service.transition(event, EventLifecycleAction.MANUAL_APPROVE, EventActor.operator("op-1"), null);

        verify(publisher).publishEventIndex(7L, DomainEventMessages.EVENT_INDEX_UPSERT);
        assertEquals(0, event.getAiRevisionCount());
        assertEquals(EventReviewRoute.AI, event.getReviewRoute());
    }

    @Test
    void leavingPublishedRemovesFromIndexAndRecordsOfflineReason() {
        EventEntity event = event(EventReviewStatus.APPROVED);
        service.transition(event, EventLifecycleAction.TAKE_OFFLINE, EventActor.operator("op-1"), " 内容违规 ");

        verify(publisher).publishEventIndex(7L, DomainEventMessages.EVENT_INDEX_DELETE);
        assertEquals(EventOfflineReason.OPERATOR, event.getOfflineReasonCode());
        assertEquals("内容违规", event.getOfflineReason());
        assertEquals("内容违规", lastHistory().getReason());
    }

    @Test
    void editingPublishedEventUnpublishesItAndReviewsAgain() {
        EventEntity event = event(EventReviewStatus.APPROVED);
        service.transition(event, EventLifecycleAction.REVISE_PUBLISHED, EventActor.organizer("org-1"), null);

        verify(publisher).publishEventIndex(7L, DomainEventMessages.EVENT_INDEX_DELETE);
        verify(publisher).publishEventQualityAnalysis(7L);
        assertEquals(EventStudentView.RE_REVIEW_NOTICE, EventStudentView.of(event, LocalDateTime.now()).notice());
    }

    @Test
    void relistClearsTheOfflineReason() {
        EventEntity event = event(EventReviewStatus.OFFLINE);
        event.setOfflineReasonCode(EventOfflineReason.ORGANIZER);
        event.setOfflineReason("场地调整");
        service.transition(event, EventLifecycleAction.RELIST, EventActor.operator("op-1"), null);

        assertNull(event.getOfflineReasonCode());
        assertNull(event.getOfflineReason());
        verify(publisher).publishEventIndex(7L, DomainEventMessages.EVENT_INDEX_UPSERT);
    }

    @Test
    void endingIsAnOfflineWithAFixedReason() {
        EventEntity event = event(EventReviewStatus.APPROVED);
        service.transition(event, EventLifecycleAction.END, EventActor.SYSTEM, null);

        assertEquals(EventOfflineReason.ENDED, event.getOfflineReasonCode());
        assertEquals(EventActorType.SYSTEM, lastHistory().getActorType());
    }
}
