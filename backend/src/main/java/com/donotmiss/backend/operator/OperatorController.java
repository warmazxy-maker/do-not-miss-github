package com.donotmiss.backend.operator;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.event.EventActorResolver;
import com.donotmiss.backend.event.EventDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Operator console (D3). Decisions on a manual item use the existing review endpoints
 * ({@code /api/events/{id}/review/approve}, {@code /review/reject} with a reason), offline and
 * relist use {@code /api/events/{id}/offline} and {@code /relist}; this controller adds what has no
 * home elsewhere: the queue, the detail view, spot checks and the all-events list.
 */
@RestController
@RequestMapping("/api/operator")
@RequirePermission(Permission.EVENT_REVIEW)
public class OperatorController {
    private final OperatorReviewService reviewService;
    private final EventActorResolver actorResolver;

    public OperatorController(OperatorReviewService reviewService, EventActorResolver actorResolver) {
        this.reviewService = reviewService;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/queue")
    public List<OperatorDtos.QueueItem> queue() {
        return reviewService.queue();
    }

    @GetMapping("/events/{eventId}")
    public OperatorDtos.ReviewDetail detail(@PathVariable Long eventId) {
        return reviewService.detail(eventId);
    }

    @GetMapping("/events")
    public OperatorDtos.EventPage events(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return reviewService.events(status, keyword, page, size);
    }

    @PostMapping("/spot-checks/draw")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.spotCheckDraw")
    public OperatorDtos.DrawResult draw(HttpServletRequest request) {
        return reviewService.drawSpotChecks(actorResolver.resolve(request).userId());
    }

    @PostMapping("/spot-checks/{eventId}/pass")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.spotCheckPass")
    public void pass(@PathVariable Long eventId, HttpServletRequest request) {
        reviewService.passSpotCheck(eventId, actorResolver.resolve(request).userId());
    }

    @PostMapping("/spot-checks/{eventId}/offline")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.spotCheckOffline")
    public EventDtos.OrganizerEventResponse fail(@PathVariable Long eventId,
                                                 @Valid @RequestBody EventDtos.ReasonRequest body,
                                                 HttpServletRequest request) {
        return reviewService.failSpotCheck(eventId, actorResolver.resolve(request), body.reason());
    }
}
