package com.donotmiss.backend.recommendation;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations/feedback")
public class RecommendationFeedbackController {
    private final RecommendationFeedbackService feedbackService;
    private final CurrentUser currentUser;

    public RecommendationFeedbackController(RecommendationFeedbackService feedbackService, CurrentUser currentUser) {
        this.feedbackService = feedbackService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public RecommendationFeedbackDtos.FeedbackState submit(@Valid @RequestBody RecommendationFeedbackDtos.SubmitRequest request,
                                                           HttpServletRequest servletRequest) {
        return feedbackService.submit(currentUser.id(servletRequest), request);
    }

    @GetMapping
    public List<RecommendationFeedbackDtos.FeedbackState> stateOfRun(@RequestParam Long runId, HttpServletRequest servletRequest) {
        return feedbackService.stateOfRun(currentUser.id(servletRequest), runId);
    }
}
