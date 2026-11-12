package com.donotmiss.backend.agentkit;

public enum ToolResultStatus {
    OK,
    /** Bad arguments, a timeout, an exception, a rate limit: the model is told why. */
    ERROR,
    /** Not on the agent's list, switched off, or the student lacks the permission. */
    DENIED,
    /** A write that waits for the student to press confirm (F2). */
    PENDING_CONFIRMATION
}
