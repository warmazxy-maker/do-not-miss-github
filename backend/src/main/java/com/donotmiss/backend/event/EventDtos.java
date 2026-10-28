package com.donotmiss.backend.event;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public class EventDtos {
    /**
     * Create or edit an event. {@code submit}: send it to review right away. When omitted, creating
     * submits (the old behaviour, so older clients keep working) and editing only saves.
     */
    public record CreateEventRequest(
            @NotBlank String title,
            @NotBlank String organizationName,
            @NotBlank String category,
            @NotNull LocalDateTime startTime,
            LocalDateTime endTime,
            @NotBlank String location,
            @NotBlank String content,
            @NotBlank String benefitType,
            String skill,
            BigDecimal moneyAmount,
            Boolean submit
    ) {
    }

    /** Taking an event offline, or asking for a revision: the reason is shown to the other side. */
    public record ReasonRequest(@NotBlank @Size(max = EventLifecycleRules.REASON_MAX_LENGTH) String reason) {
    }

    public record EventSearchRequest(
            String keyword,
            String category,
            String benefitType,
            String location
    ) {
    }

    /**
     * What students (and anyone searching) see. No review internals: an event under re-review or
     * taken down simply shows as "已下架" with a student-facing reason, see {@link EventStudentView}.
     */
    public record EventResponse(
            Long id,
            String title,
            String organizationName,
            String category,
            String categoryCode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String timePhase,
            String timePhaseLabel,
            String studentStatus,
            String studentStatusLabel,
            String studentNotice,
            String location,
            String content,
            String benefitType,
            String benefitTypeCode,
            String skill,
            BigDecimal moneyAmount,
            String createdByUserId,
            Instant createdAt
    ) {
        public static EventResponse from(EventEntity event) {
            return from(event, LocalDateTime.now());
        }

        public static EventResponse from(EventEntity event, LocalDateTime now) {
            EventTimePhase phase = event.timePhase(now);
            EventStudentView view = EventStudentView.of(event, now);
            return new EventResponse(
                    event.getId(),
                    event.getTitle(),
                    event.getOrganizationName(),
                    event.getCategory().label(),
                    event.getCategory().name(),
                    event.getStartTime(),
                    event.getEndTime(),
                    phase.name(),
                    phase.label(),
                    view.status(),
                    view.label(),
                    view.notice(),
                    event.getLocation(),
                    event.getContent(),
                    event.getBenefitType().label(),
                    event.getBenefitType().name(),
                    event.getSkill(),
                    event.getMoneyAmount(),
                    event.getCreatedByUserId(),
                    event.getCreatedAt()
            );
        }
    }

    /**
     * What the organisation (and operators) see about an event: the public fields plus the
     * publication state, why it is there, and which actions are possible now, so the page does not
     * have to re-derive the state machine.
     */
    public record OrganizerEventResponse(
            @JsonUnwrapped EventResponse event,
            String reviewStatus,
            String reviewStatusLabel,
            String reviewRoute,
            String offlineReasonCode,
            String offlineReason,
            String revisionNote,
            int aiRevisionCount,
            long version,
            boolean canEdit,
            boolean canSubmit,
            boolean canTakeOffline,
            boolean canDiscard,
            boolean canCheckIn
    ) {
        public static OrganizerEventResponse from(EventEntity event) {
            LocalDateTime now = LocalDateTime.now();
            EventReviewStatus status = event.getReviewStatus();
            boolean discarded = event.getOfflineReasonCode() == EventOfflineReason.DISCARDED;
            return new OrganizerEventResponse(
                    EventResponse.from(event, now),
                    status.name(),
                    status.label(),
                    event.getReviewRoute().name(),
                    event.getOfflineReasonCode() == null ? null : event.getOfflineReasonCode().name(),
                    event.getOfflineReason(),
                    status == EventReviewStatus.NEEDS_REVISION ? event.getRevisionNote() : null,
                    event.getAiRevisionCount(),
                    event.getVersion(),
                    status != EventReviewStatus.PENDING_REVIEW && !discarded,
                    EventLifecycleRules.allows(status, EventLifecycleAction.SUBMIT) && !discarded,
                    EventLifecycleRules.allows(status, EventLifecycleAction.TAKE_OFFLINE),
                    EventLifecycleRules.allows(status, EventLifecycleAction.DISCARD),
                    canCheckIn(event)
            );
        }

        /** Check-in is open while the event is published, and right after it ended. */
        public static boolean canCheckIn(EventEntity event) {
            return event.getReviewStatus() == EventReviewStatus.APPROVED
                    || (event.getReviewStatus() == EventReviewStatus.OFFLINE
                    && event.getOfflineReasonCode() == EventOfflineReason.ENDED);
        }
    }

    public record HistoryResponse(
            String fromStatus,
            String fromStatusLabel,
            String toStatus,
            String toStatusLabel,
            String action,
            String actorType,
            String reason,
            Instant createdAt
    ) {
        public static HistoryResponse from(EventStatusHistoryEntity row) {
            return new HistoryResponse(
                    row.getFromStatus() == null ? null : row.getFromStatus().name(),
                    row.getFromStatus() == null ? null : row.getFromStatus().label(),
                    row.getToStatus().name(),
                    row.getToStatus().label(),
                    row.getAction(),
                    row.getActorType().name(),
                    row.getReason(),
                    row.getCreatedAt()
            );
        }
    }
}
