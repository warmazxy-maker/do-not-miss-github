package com.donotmiss.backend.eventquality;

import com.donotmiss.backend.event.EventReviewStatus;
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

    public EventQualityController(EventQualityService eventQualityService) {
        this.eventQualityService = eventQualityService;
    }

    @GetMapping("/{eventId}/quality-report")
    public EventQualityDtos.ReportResponse report(@PathVariable Long eventId) {
        return eventQualityService.getReport(eventId);
    }

    @PostMapping("/{eventId}/quality/reanalyze")
    public Map<String, String> reanalyze(@PathVariable Long eventId) {
        eventQualityService.requestReanalysis(eventId);
        return Map.of("status", EventReviewStatus.PENDING_REVIEW.name());
    }

    @PostMapping("/{eventId}/review/approve")
    public Map<String, String> approve(@PathVariable Long eventId) {
        EventReviewStatus status = eventQualityService.approve(eventId);
        return Map.of("status", status.name(), "label", status.label());
    }

    @PostMapping("/{eventId}/review/reject")
    public Map<String, String> reject(@PathVariable Long eventId) {
        EventReviewStatus status = eventQualityService.reject(eventId);
        return Map.of("status", status.name(), "label", status.label());
    }
}
