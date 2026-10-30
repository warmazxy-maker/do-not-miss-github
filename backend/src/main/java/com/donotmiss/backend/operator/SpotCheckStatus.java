package com.donotmiss.backend.operator;

public enum SpotCheckStatus {
    /** Drawn, waiting in the queue. */
    PENDING,
    /** The operator found nothing wrong. */
    PASSED,
    /** The operator took the event offline with a reason; the organisation revises and resubmits. */
    TAKEN_OFFLINE
}
