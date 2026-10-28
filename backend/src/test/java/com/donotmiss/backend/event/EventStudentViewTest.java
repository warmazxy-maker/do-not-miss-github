package com.donotmiss.backend.event;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** The student sees one status that folds publication and time together. */
class EventStudentViewTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);

    private static EventEntity event(EventReviewStatus status, LocalDateTime start, LocalDateTime end) {
        EventEntity event = new EventEntity();
        event.setStartTime(start);
        event.setEndTime(end);
        event.setReviewStatus(status);
        return event;
    }

    @Test
    void publishedEventShowsItsTimePhase() {
        assertEquals("NOT_STARTED", EventStudentView.of(event(EventReviewStatus.APPROVED, NOW.plusHours(1), NOW.plusHours(3)), NOW).status());
        assertEquals("ONGOING", EventStudentView.of(event(EventReviewStatus.APPROVED, NOW.minusHours(1), NOW.plusHours(1)), NOW).status());
        // Ended before the expiry job got to it: still shown as ended, nothing waits on the job.
        assertTrue(EventStudentView.of(event(EventReviewStatus.APPROVED, NOW.minusHours(3), NOW.minusHours(1)), NOW).ended());
    }

    @Test
    void endOfTimeIsExclusive() {
        EventEntity event = event(EventReviewStatus.APPROVED, NOW.minusHours(2), NOW);
        assertTrue(event.hasEnded(NOW));
        assertFalse(event.hasEnded(NOW.minusSeconds(1)));
    }

    @Test
    void offlineShowsTheWrittenReason() {
        EventEntity event = event(EventReviewStatus.OFFLINE, NOW.plusDays(1), NOW.plusDays(2));
        event.setOfflineReasonCode(EventOfflineReason.ORGANIZER);
        event.setOfflineReason("场地调整");
        EventStudentView view = EventStudentView.of(event, NOW);
        assertTrue(view.offline());
        assertEquals("场地调整", view.notice());
    }

    @Test
    void endedOfflineIsShownAsEnded() {
        EventEntity event = event(EventReviewStatus.OFFLINE, NOW.minusDays(2), NOW.minusDays(1));
        event.setOfflineReasonCode(EventOfflineReason.ENDED);
        assertTrue(EventStudentView.of(event, NOW).ended());
    }

    @Test
    void reReviewIsOfflineWithoutRevisionDetails() {
        for (EventReviewStatus status : new EventReviewStatus[]{EventReviewStatus.PENDING_REVIEW, EventReviewStatus.NEEDS_REVISION}) {
            EventEntity event = event(status, NOW.plusDays(1), NOW.plusDays(2));
            event.setRevisionNote("内部审核意见");
            EventStudentView view = EventStudentView.of(event, NOW);
            assertTrue(view.offline());
            assertEquals(EventStudentView.RE_REVIEW_NOTICE, view.notice());
        }
    }
}
