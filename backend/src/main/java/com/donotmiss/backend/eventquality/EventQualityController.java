package com.donotmiss.backend.eventquality;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.event.EventActorResolver;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventReviewStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventQualityController {
    private final EventQualityService eventQualityService;
    private final EventReviewAccess access;
    private final EventActorResolver actorResolver;

    public EventQualityController(EventQualityService eventQualityService,
                                  EventReviewAccess access,
                                  EventActorResolver actorResolver) {
        this.eventQualityService = eventQualityService;
        this.access = access;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/{eventId}/quality-report")
    @RequirePermission({Permission.EVENT_PUBLISH, Permission.EVENT_REVIEW})
    public EventQualityDtos.ReportResponse report(@PathVariable Long eventId, HttpServletRequest request) {
        access.requirePublisherOrReviewer(request, eventId);
        return eventQualityService.getReport(eventId);
    }

    /** Ownership is checked by the state machine (an organiser may only act on its own event). */
    @PostMapping("/{eventId}/quality/reanalyze")
    @RequirePermission(value = {Permission.EVENT_PUBLISH, Permission.EVENT_REVIEW}, action = "event.reanalyze")
    public Map<String, String> reanalyze(@PathVariable Long eventId, HttpServletRequest request) {
        return statusBody(eventQualityService.requestReanalysis(eventId, actorResolver.resolve(request)));
    }

    /** Previously open to anyone, including anonymous callers (D0 fix, D1 permission). */
    @PostMapping("/{eventId}/review/approve")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.approve")
    public Map<String, String> approve(@PathVariable Long eventId, HttpServletRequest request) {
        return statusBody(eventQualityService.approve(eventId, actorResolver.resolve(request)));
    }

    /** Since D2 this asks for a revision (with a note the organisation sees); there is no final rejection. */
    @PostMapping("/{eventId}/review/reject")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.request_revision")
    public Map<String, String> reject(@PathVariable Long eventId,
                                      @Valid @RequestBody EventDtos.ReasonRequest body,
                                      HttpServletRequest request) {
        return statusBody(eventQualityService.requestRevision(eventId, actorResolver.resolve(request), body.reason()));
    }

    private Map<String, String> statusBody(EventReviewStatus status) {
        return Map.of("status", status.name(), "label", status.label());
    }
}
