package com.donotmiss.backend.operator;

import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.*;
import com.donotmiss.backend.eventquality.EventQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class OperatorReviewServiceTest {
    private EventRepository eventRepository;
    private EventStatusHistoryRepository historyRepository;
    private EventSpotCheckRepository spotCheckRepository;
    private EventService eventService;
    private OperatorReviewService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        historyRepository = mock(EventStatusHistoryRepository.class);
        spotCheckRepository = mock(EventSpotCheckRepository.class);
        eventService = mock(EventService.class);
        service = new OperatorReviewService(eventRepository, historyRepository, spotCheckRepository,
                mock(EventQualityService.class), eventService, mock(JdbcTemplate.class));
        when(spotCheckRepository.findByStatusOrderByDrawnAtAsc(SpotCheckStatus.PENDING)).thenReturn(List.of());
        when(historyRepository.findFirstByEventIdAndToStatusOrderByIdDesc(anyLong(), any())).thenReturn(Optional.empty());
    }

    private static EventEntity event(long id, EventReviewStatus status, Instant createdAt) {
        EventEntity event = EventEntity.detached(id);
        event.setTitle("活动" + id);
        event.setOrganizationName("组织");
        event.setStartTime(LocalDateTime.now().plusDays(1));
        event.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        ReflectionTestUtils.setField(event, "reviewStatus", status);
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        return event;
    }

    private static EventSpotCheckEntity check(long eventId, Instant drawnAt) {
        EventSpotCheckEntity check = EventSpotCheckEntity.drawn(eventId, "op-1");
        ReflectionTestUtils.setField(check, "drawnAt", drawnAt);
        return check;
    }

    @Test
    void queueIsFirstInFirstOutAcrossBothKinds() {
        Instant t0 = Instant.parse("2026-10-30T01:00:00Z");
        EventEntity manual = event(1, EventReviewStatus.PENDING_REVIEW, t0.plusSeconds(600));
        EventEntity spot = event(2, EventReviewStatus.APPROVED, t0);
        when(eventRepository.findByReviewStatusAndReviewRoute(EventReviewStatus.PENDING_REVIEW, EventReviewRoute.MANUAL))
                .thenReturn(List.of(manual));
        when(spotCheckRepository.findByStatusOrderByDrawnAtAsc(SpotCheckStatus.PENDING))
                .thenReturn(List.of(check(2, t0.plusSeconds(60))));
        when(eventRepository.findById(2L)).thenReturn(Optional.of(spot));

        List<OperatorDtos.QueueItem> queue = service.queue();

        assertEquals(List.of(2L, 1L), queue.stream().map(OperatorDtos.QueueItem::eventId).toList());
        assertEquals(OperatorDtos.QueueKind.SPOT_CHECK, queue.get(0).kind());
        assertEquals(OperatorDtos.QueueKind.MANUAL, queue.get(1).kind());
    }

    @Test
    void spotCheckOfAnEventNoLongerPublishedIsNotShown() {
        when(eventRepository.findByReviewStatusAndReviewRoute(any(), any())).thenReturn(List.of());
        when(spotCheckRepository.findByStatusOrderByDrawnAtAsc(SpotCheckStatus.PENDING))
                .thenReturn(List.of(check(3, Instant.now())));
        when(eventRepository.findById(3L)).thenReturn(Optional.of(event(3, EventReviewStatus.OFFLINE, Instant.now())));

        assertTrue(service.queue().isEmpty());
    }

    @Test
    void passingResolvesTheCheckAndLeavesTheEventAlone() {
        EventSpotCheckEntity check = check(4, Instant.now());
        when(spotCheckRepository.findByEventIdAndStatus(4L, SpotCheckStatus.PENDING)).thenReturn(Optional.of(check));

        service.passSpotCheck(4L, "op-2");

        assertEquals(SpotCheckStatus.PASSED, check.getStatus());
        verifyNoInteractions(eventService);
    }

    @Test
    void failingTakesTheEventOfflineThroughTheStateMachine() {
        EventSpotCheckEntity check = check(5, Instant.now());
        when(spotCheckRepository.findByEventIdAndStatus(5L, SpotCheckStatus.PENDING)).thenReturn(Optional.of(check));
        EventActor operator = EventActor.operator("op-2");

        service.failSpotCheck(5L, operator, "报名方式是外部链接，无法核实");

        verify(eventService).takeOffline(5L, operator, "报名方式是外部链接，无法核实");
        assertEquals(SpotCheckStatus.TAKEN_OFFLINE, check.getStatus());
    }

    @Test
    void secondOperatorOnTheSameCheckGetsAConflict() {
        when(spotCheckRepository.findByEventIdAndStatus(6L, SpotCheckStatus.PENDING)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.passSpotCheck(6L, "op-3"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }
}
