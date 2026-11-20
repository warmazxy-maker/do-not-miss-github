package com.donotmiss.backend.master.wake;

public enum WakeTriggerStatus {
    PENDING,
    PROCESSING,
    /** Said, in the message {@code message_id}. */
    DONE,
    /** Not said, on purpose: switched off, no longer true, or Tandem judged it not worth a message. */
    SKIPPED,
    /** The model failed twice. */
    FAILED
}
