package com.donotmiss.backend.event;

/** Why an OFFLINE event is offline. */
public enum EventOfflineReason {
    /** Taken down by its organisation, with a written reason. */
    ORGANIZER,
    /** Taken down by an operator, with a written reason. */
    OPERATOR,
    /** Its end time passed; the expiry job moved it here. Cannot be relisted unless the time is changed. */
    ENDED,
    /** A draft (or an event that was never approved) that the organisation dropped. Terminal. */
    DISCARDED
}
