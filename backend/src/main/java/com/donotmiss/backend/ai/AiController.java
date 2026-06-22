package com.donotmiss.backend.ai;

import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.memory.UserMemoryDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiService aiService;
    private final CurrentUser currentUser;

    public AiController(AiService aiService, CurrentUser currentUser) {
        this.aiService = aiService;
        this.currentUser = currentUser;
    }

    @PostMapping("/event-recommendations")
    public AiDtos.EventRecommendationResponse recommendEvents(@Valid @RequestBody AiDtos.EventRecommendationRequest request,
                                                              HttpServletRequest servletRequest) {
        return aiService.recommendEvents(currentUser.id(servletRequest), request);
    }

    @PostMapping("/action-plans")
    public AiDtos.PlanRecommendationResponse recommendPlans(@Valid @RequestBody AiDtos.PlanRecommendationRequest request,
                                                            HttpServletRequest servletRequest) {
        return aiService.recommendPlans(currentUser.id(servletRequest), request);
    }

    @PostMapping("/self-analysis")
    public AiDtos.SelfAnalysisResponse selfAnalysis(HttpServletRequest servletRequest) {
        return aiService.selfAnalysis(currentUser.id(servletRequest));
    }

    @GetMapping("/profile-memory")
    public UserMemoryDtos.Profile profileMemory(HttpServletRequest servletRequest) {
        return aiService.profileMemory(currentUser.id(servletRequest));
    }
}
