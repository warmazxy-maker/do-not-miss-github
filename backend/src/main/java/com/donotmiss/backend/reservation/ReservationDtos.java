package com.donotmiss.backend.reservation;

import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventStudentView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDateTime;

public class ReservationDtos {
    public record CreateReservationRequest(@NotNull Long eventId) {
    }

    /** The organisation scans (or types) the student's QR code at the venue. */
    public record CheckInRequest(@NotBlank String qrToken) {
    }

    /**
     * {@code notice}: a prominent line for a reservation whose event is no longer running normally.
     * The reservation itself is never cancelled for the student (D2, L2); they decide what to do.
     */
    public record ReservationResponse(
            Long id,
            EventDtos.EventResponse event,
            String status,
            String qrToken,
            Instant reservedAt,
            Instant completedAt,
            String notice
    ) {
        public static final String ENDED_NOTICE = "活动已结束，请处理";

        public static ReservationResponse from(ReservationEntity reservation) {
            LocalDateTime now = LocalDateTime.now();
            EventDtos.EventResponse event = EventDtos.EventResponse.from(reservation.getEvent(), now);
            return new ReservationResponse(
                    reservation.getId(),
                    event,
                    reservation.getStatus().name(),
                    reservation.getQrToken(),
                    reservation.getReservedAt(),
                    reservation.getCompletedAt(),
                    reservation.getStatus() == ReservationStatus.RESERVED ? noticeFor(event) : null
            );
        }

        static String noticeFor(EventDtos.EventResponse event) {
            if (EventStudentView.OFFLINE.equals(event.studentStatus())) {
                return "活动已下架：" + (event.studentNotice() == null ? "发布方未说明原因" : event.studentNotice());
            }
            if ("ENDED".equals(event.studentStatus())) {
                return ENDED_NOTICE;
            }
            return null;
        }
    }

    /** What the organisation sees after a successful check-in. */
    public record CheckInResponse(Long reservationId, String studentUserId, Instant completedAt) {
    }
}
