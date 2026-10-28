package com.donotmiss.backend.event;

import com.donotmiss.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EventLifecycleRulesTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);
    private static final EventActor OWNER = EventActor.organizer("org-1");
    private static final EventActor OTHER_ORG = EventActor.organizer("org-2");
    private static final EventActor OPERATOR = EventActor.operator("op-1");

    static EventEntity event(EventReviewStatus status) {
        EventEntity event = new EventEntity();
        event.setCreatedByUserId("org-1");
        event.setStartTime(NOW.plusDays(1));
        event.setEndTime(NOW.plusDays(1).plusHours(2));
        event.setReviewStatus(status);
        return event;
    }

    private static HttpStatus statusOf(Runnable call) {
        return assertThrows(ApiException.class, call::run).getStatus();
    }

    @Test
    void happyPathDraftToPublishedToOffline() {
        assertEquals(EventReviewStatus.PENDING_REVIEW,
                EventLifecycleRules.check(event(EventReviewStatus.DRAFT), EventLifecycleAction.SUBMIT, OWNER, null, NOW));
        assertEquals(EventReviewStatus.APPROVED,
                EventLifecycleRules.check(event(EventReviewStatus.PENDING_REVIEW), EventLifecycleAction.AI_APPROVE, EventActor.AI, null, NOW));
        assertEquals(EventReviewStatus.OFFLINE,
                EventLifecycleRules.check(event(EventReviewStatus.APPROVED), EventLifecycleAction.TAKE_OFFLINE, OWNER, "场地取消", NOW));
    }

    @Test
    void actionThatDoesNotFitTheStateIsAConflict() {
        // Cannot edit-and-resubmit while the reviewer is looking at it.
        assertEquals(HttpStatus.CONFLICT, statusOf(() ->
                EventLifecycleRules.check(event(EventReviewStatus.PENDING_REVIEW), EventLifecycleAction.SUBMIT, OWNER, null, NOW)));
        // The AI cannot approve something that is not waiting for review (stale message).
        assertEquals(HttpStatus.CONFLICT, statusOf(() ->
                EventLifecycleRules.check(event(EventReviewStatus.APPROVED), EventLifecycleAction.AI_APPROVE, EventActor.AI, null, NOW)));
    }

    @Test
    void organizerCannotRelistAndCannotTouchOthersEvents() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() ->
                EventLifecycleRules.check(event(EventReviewStatus.OFFLINE), EventLifecycleAction.RELIST, OWNER, null, NOW)));
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() ->
                EventLifecycleRules.check(event(EventReviewStatus.APPROVED), EventLifecycleAction.TAKE_OFFLINE, OTHER_ORG, "x", NOW)));
        assertEquals(EventReviewStatus.APPROVED,
                EventLifecycleRules.check(event(EventReviewStatus.OFFLINE), EventLifecycleAction.RELIST, OPERATOR, null, NOW));
    }

    @Test
    void operatorMayTakeAnyEventOffline() {
        assertEquals(EventReviewStatus.OFFLINE,
                EventLifecycleRules.check(event(EventReviewStatus.APPROVED), EventLifecycleAction.TAKE_OFFLINE, OPERATOR, "违规", NOW));
    }

    @Test
    void offlineAndRevisionRequestNeedAReason() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() ->
                EventLifecycleRules.check(event(EventReviewStatus.APPROVED), EventLifecycleAction.TAKE_OFFLINE, OWNER, "  ", NOW)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() ->
                EventLifecycleRules.check(event(EventReviewStatus.PENDING_REVIEW), EventLifecycleAction.MANUAL_REQUEST_REVISION, OPERATOR, null, NOW)));
    }

    @Test
    void endedEventCannotBeSubmittedOrRelisted() {
        EventEntity ended = event(EventReviewStatus.OFFLINE);
        ended.setStartTime(NOW.minusDays(2));
        ended.setEndTime(NOW.minusDays(1));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() ->
                EventLifecycleRules.check(ended, EventLifecycleAction.RELIST, OPERATOR, null, NOW)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() ->
                EventLifecycleRules.check(ended, EventLifecycleAction.SUBMIT, OWNER, null, NOW)));
    }

    @Test
    void discardedEventIsTerminal() {
        EventEntity discarded = event(EventReviewStatus.OFFLINE);
        discarded.setOfflineReasonCode(EventOfflineReason.DISCARDED);
        assertEquals(HttpStatus.CONFLICT, statusOf(() ->
                EventLifecycleRules.check(discarded, EventLifecycleAction.SUBMIT, OWNER, null, NOW)));
        assertEquals(HttpStatus.CONFLICT, statusOf(() ->
                EventLifecycleRules.check(discarded, EventLifecycleAction.RELIST, OPERATOR, null, NOW)));
    }

    @Test
    void onlyNeverPublishedEventsCanBeDiscarded() {
        assertTrue(EventLifecycleRules.allows(EventReviewStatus.DRAFT, EventLifecycleAction.DISCARD));
        assertTrue(EventLifecycleRules.allows(EventReviewStatus.NEEDS_REVISION, EventLifecycleAction.DISCARD));
        assertFalse(EventLifecycleRules.allows(EventReviewStatus.APPROVED, EventLifecycleAction.DISCARD));
    }

    @Test
    void thirdAiRevisionRoutesTheNextSubmissionToAPerson() {
        EventEntity event = event(EventReviewStatus.NEEDS_REVISION);
        event.setAiRevisionCount(2);
        assertEquals(EventReviewRoute.AI, EventLifecycleRules.routeFor(event, EventLifecycleAction.SUBMIT, OWNER));
        event.setAiRevisionCount(EventLifecycleRules.AI_REVISION_LIMIT);
        assertEquals(EventReviewRoute.MANUAL, EventLifecycleRules.routeFor(event, EventLifecycleAction.SUBMIT, OWNER));
        // Organiser cannot dodge the person by asking for re-analysis; an operator can send it back to the AI.
        assertEquals(EventReviewRoute.MANUAL, EventLifecycleRules.routeFor(event, EventLifecycleAction.REANALYZE, OWNER));
        assertEquals(EventReviewRoute.AI, EventLifecycleRules.routeFor(event, EventLifecycleAction.REANALYZE, OPERATOR));
    }
}
