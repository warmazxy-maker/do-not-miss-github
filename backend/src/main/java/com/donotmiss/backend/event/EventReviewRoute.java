package com.donotmiss.backend.event;

/** Who reviews the current submission. */
public enum EventReviewRoute {
    AI,
    /** After {@link EventLifecycleRules#AI_REVISION_LIMIT} AI revision requests, a person decides (D3 queue). */
    MANUAL
}
