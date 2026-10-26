package com.donotmiss.backend.eventquality;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.event.EventReviewStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventQualityController {
    private final EventQualityService eventQualityService;
    private final EventReviewAccess access;

    public EventQualityController(EventQualityService eventQualityService, EventReviewAccess access) {
        this.eventQualityService = eventQualityService;
        this.access = access;
    }

    @GetMapping("/{eventId}/quality-report")
    @RequirePermission({Permission.EVENT_PUBLISH, Permission.EVENT_REVIEW})
    public EventQualityDtos.ReportResponse report(@PathVariable Long eventId, HttpServletRequest request) {
        access.requirePublisherOrReviewer(request, eventId);
        return eventQualityService.getReport(eventId);
    }

    @PostMapping("/{eventId}/quality/reanalyze")
    @RequirePermission(value = {Permission.EVENT_PUBLISH, Permission.EVENT_REVIEW}, action = "event.reanalyze")
    public Map<String, String> reanalyze(@PathVariable Long eventId, HttpServletRequest request) {
        access.requirePublisherOrReviewer(request, eventId);
        eventQualityService.requestReanalysis(eventId);
        return Map.of("status", EventReviewStatus.PENDING_REVIEW.name());
    }

    /** Previously open to anyone, including anonymous callers (D0 fix, D1 permission). */
    @PostMapping("/{eventId}/review/approve")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.approve")
    public Map<String, String> approve(@PathVariable Long eventId) {
        EventReviewStatus status = eventQualityService.approve(eventId);
        return Map.of("status", status.name(), "label", status.label());
    }

    @PostMapping("/{eventId}/review/reject")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.reject")
    public Map<String, String> reject(@PathVariable Long eventId) {
        EventReviewStatus status = eventQualityService.reject(eventId);
        return Map.of("status", status.name(), "label", status.label());
    }
}
