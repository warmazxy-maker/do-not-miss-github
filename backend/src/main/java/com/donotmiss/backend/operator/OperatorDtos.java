package com.donotmiss.backend.operator;

import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.eventquality.EventQualityDtos;

import java.time.Instant;
import java.util.List;

public class OperatorDtos {
    /** Why an event is in the review queue. */
    public enum QueueKind {
        /** The AI asked for revision three times; a person decides this submission. */
        MANUAL,
        /** Approved by the AI, drawn for a second look. The event stays published meanwhile. */
        SPOT_CHECK
    }

    /** One row of the queue. First in, first out by {@code waitingSince}. */
    public record QueueItem(
            Long eventId,
            String title,
            String organizationName,
            QueueKind kind,
            Instant waitingSince,
            int aiRevisionCount
    ) {
    }

    /**
     * What an operator needs to decide (D3, L3): the event, the latest full AI report, and the
     * revision notes the AI wrote each time it sent the event back. Earlier full reports are not
     * kept (each analysis overwrites the report); their key points live on in those notes.
     */
    public record ReviewDetail(
            EventDtos.OrganizerEventResponse event,
            EventQualityDtos.ReportResponse latestReport,
            List<RevisionNote> aiRevisionNotes,
            List<EventDtos.HistoryResponse> history,
            boolean pendingSpotCheck
    ) {
    }

    public record RevisionNote(String note, Instant createdAt) {
    }

    public record DrawResult(int drawn) {
    }

    public record EventPage(List<EventDtos.OrganizerEventResponse> items, long total, int page, int size) {
    }
}
