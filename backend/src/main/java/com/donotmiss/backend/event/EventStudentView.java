package com.donotmiss.backend.event;

import java.time.LocalDateTime;

/**
 * The student's single status for an event, combining the two dimensions so the page never has to.
 * Students only ever met published events (search shows nothing else), so any non-published state
 * means "it was there and now it is not":
 * <ul>
 *   <li>APPROVED: the time phase (未开始 / 进行中 / 已结束);</li>
 *   <li>OFFLINE because it ended: 已结束;</li>
 *   <li>OFFLINE for any other reason: 已下架, with the written reason;</li>
 *   <li>back in review after an edit: 已下架, "活动重新上架审核中" (the revision details are not the student's business).</li>
 * </ul>
 */
public record EventStudentView(String status, String label, String notice) {
    public static final String OFFLINE = "OFFLINE";
    public static final String RE_REVIEW_NOTICE = "活动重新上架审核中";

    public static EventStudentView of(EventEntity event, LocalDateTime now) {
        return switch (event.getReviewStatus()) {
            case APPROVED -> {
                EventTimePhase phase = event.timePhase(now);
                yield new EventStudentView(phase.name(), phase.label(), null);
            }
            case OFFLINE -> event.getOfflineReasonCode() == EventOfflineReason.ENDED
                    ? new EventStudentView(EventTimePhase.ENDED.name(), EventTimePhase.ENDED.label(), EventLifecycleService.ENDED_REASON)
                    : new EventStudentView(OFFLINE, EventReviewStatus.OFFLINE.label(), event.getOfflineReason());
            default -> new EventStudentView(OFFLINE, EventReviewStatus.OFFLINE.label(), RE_REVIEW_NOTICE);
        };
    }

    public boolean ended() {
        return EventTimePhase.ENDED.name().equals(status);
    }

    public boolean offline() {
        return OFFLINE.equals(status);
    }
}
