package com.donotmiss.backend.coach;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/coach")
public class CoachController {
    private final CoachService coachService;
    private final CurrentUser currentUser;

    public CoachController(CoachService coachService, CurrentUser currentUser) {
        this.coachService = coachService;
        this.currentUser = currentUser;
    }

    @GetMapping("/messages")
    public List<CoachDtos.CoachMessageResponse> messages(@RequestParam(required = false) LocalDate date,
                                                         HttpServletRequest request) {
        return coachService.messages(currentUser.id(request), date);
    }

    @PostMapping("/chat")
    public CoachDtos.ChatResponse chat(@Valid @RequestBody CoachDtos.ChatRequest request,
                                       HttpServletRequest servletRequest) {
        return coachService.chat(currentUser.id(servletRequest), request);
    }

    @GetMapping("/logs")
    public List<CoachDtos.CoachLogResponse> logs(HttpServletRequest request) {
        return coachService.logs(currentUser.id(request));
    }

    @GetMapping("/memory-reviews")
    public List<CoachDtos.CoachMemoryReviewResponse> memoryReviews(HttpServletRequest request) {
        return coachService.memoryReviews(currentUser.id(request));
    }

    @PostMapping("/logs/generate")
    public CoachDtos.CoachLogResponse generateLog(@RequestBody(required = false) CoachDtos.GenerateLogRequest request,
                                                  HttpServletRequest servletRequest) {
        CoachDtos.GenerateLogRequest body = request == null ? new CoachDtos.GenerateLogRequest(null) : request;
        return coachService.generateLog(currentUser.id(servletRequest), body);
    }
}
