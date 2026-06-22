package com.donotmiss.backend.reservation;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final ReservationService reservationService;
    private final CurrentUser currentUser;

    public ReservationController(ReservationService reservationService, CurrentUser currentUser) {
        this.reservationService = reservationService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ReservationDtos.ReservationResponse> list(HttpServletRequest request) {
        return reservationService.listActive(currentUser.id(request));
    }

    @PostMapping
    public ReservationDtos.ReservationResponse reserve(@Valid @RequestBody ReservationDtos.CreateReservationRequest request,
                                                       HttpServletRequest servletRequest) {
        return reservationService.reserve(currentUser.id(servletRequest), request.eventId());
    }

    @DeleteMapping("/{reservationId}")
    public void cancel(@PathVariable Long reservationId, HttpServletRequest request) {
        reservationService.cancel(currentUser.id(request), reservationId);
    }

    /**
     * 模拟扫码完成：真实产品里二维码内容就是 qrToken。
     */
    @PostMapping("/scan-complete")
    public ReservationDtos.ReservationResponse scanComplete(@Valid @RequestBody ReservationDtos.ScanCompleteRequest request,
                                                            HttpServletRequest servletRequest) {
        return reservationService.scanComplete(currentUser.id(servletRequest), request.qrToken());
    }
}
