package com.donotmiss.backend.event;

import java.time.LocalDateTime;

/**
 * Time dimension of an event: derived from start and end time on every read, never stored,
 * so there is nothing to keep in sync and no job has to "refresh" it.
 */
public enum EventTimePhase {
    NOT_STARTED("未开始"),
    ONGOING("进行中"),
    ENDED("已结束");

    private final String label;

    EventTimePhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static EventTimePhase of(EventEntity event, LocalDateTime now) {
        if (event.hasEnded(now)) {
            return ENDED;
        }
        return event.getStartTime() != null && event.getStartTime().isAfter(now) ? NOT_STARTED : ONGOING;
    }
}
