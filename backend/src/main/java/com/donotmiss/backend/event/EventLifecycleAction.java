package com.donotmiss.backend.event;

/** Everything that can happen to an event's publication state. */
public enum EventLifecycleAction {
    /** Organisation sends a draft, a revised event or an offline event (after editing) to review. */
    SUBMIT,
    /** Organisation edits a published event: it goes back to review, students see it as offline meanwhile. */
    REVISE_PUBLISHED,
    /** Run the review again on the same content (stuck message, or the organisation disagrees). */
    REANALYZE,
    AI_APPROVE,
    AI_REQUEST_REVISION,
    MANUAL_APPROVE,
    MANUAL_REQUEST_REVISION,
    /** Organisation (own event) or operator takes a published event down; a reason is required. */
    TAKE_OFFLINE,
    /** The expiry job: a published event whose end time passed. */
    END,
    /** Operator puts an offline event straight back. Organisations cannot; they edit and resubmit. */
    RELIST,
    /** Organisation drops an event that was never published. */
    DISCARD
}
