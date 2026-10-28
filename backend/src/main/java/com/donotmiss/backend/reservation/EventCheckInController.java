package com.donotmiss.backend.reservation;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organisation side of check-in. Lives next to reservations (it completes one) but sits under the
 * event's URL, because the organisation acts on its event, not on a student's reservation list.
 */
@RestController
public class EventCheckInController {
    private final ReservationService reservationService;
    private final CurrentUser currentUser;

    public EventCheckInController(ReservationService reservationService, CurrentUser currentUser) {
        this.reservationService = reservationService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/events/{eventId}/check-in")
    @RequirePermission(value = Permission.EVENT_PUBLISH, action = "event.check_in")
    public ReservationDtos.CheckInResponse checkIn(@PathVariable Long eventId,
                                                   @Valid @RequestBody ReservationDtos.CheckInRequest request,
                                                   HttpServletRequest servletRequest) {
        return reservationService.checkIn(eventId, currentUser.id(servletRequest), request.qrToken());
    }
}
