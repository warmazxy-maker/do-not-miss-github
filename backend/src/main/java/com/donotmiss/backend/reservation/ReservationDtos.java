package com.donotmiss.backend.reservation;

import com.donotmiss.backend.event.EventDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class ReservationDtos {
    public record CreateReservationRequest(@NotNull Long eventId) {
    }

    public record ScanCompleteRequest(@NotBlank String qrToken) {
    }

    public record ReservationResponse(
            Long id,
            EventDtos.EventResponse event,
            String status,
            String qrToken,
            Instant reservedAt,
            Instant completedAt
    ) {
        public static ReservationResponse from(ReservationEntity reservation) {
            return new ReservationResponse(
                    reservation.getId(),
                    EventDtos.EventResponse.from(reservation.getEvent()),
                    reservation.getStatus().name(),
                    reservation.getQrToken(),
                    reservation.getReservedAt(),
                    reservation.getCompletedAt()
            );
        }
    }
}
