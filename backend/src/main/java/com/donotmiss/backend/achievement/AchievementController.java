package com.donotmiss.backend.achievement;

import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/achievements")
public class AchievementController {
    private final AchievementService achievementService;
    private final GrowthTagService growthTagService;
    private final CurrentUser currentUser;

    public AchievementController(AchievementService achievementService,
                                 GrowthTagService growthTagService,
                                 CurrentUser currentUser) {
        this.achievementService = achievementService;
        this.growthTagService = growthTagService;
        this.currentUser = currentUser;
    }

    @GetMapping("/history")
    public List<AchievementDtos.AchievementRecordResponse> history(HttpServletRequest request) {
        return achievementService.history(currentUser.id(request));
    }

    @GetMapping("/history/page")
    public PageResponse<AchievementDtos.AchievementRecordResponse> historyPage(@RequestParam(defaultValue = "0") int page,
                                                                               @RequestParam(defaultValue = "5") int size,
                                                                               HttpServletRequest request) {
        return achievementService.historyPage(currentUser.id(request), page, size);
    }

    @PutMapping("/history/{recordId}/reflection")
    public AchievementDtos.AchievementRecordResponse updateReflection(@PathVariable Long recordId,
                                                                      @Valid @RequestBody AchievementDtos.ReflectionRequest request,
                                                                      HttpServletRequest servletRequest) {
        return achievementService.updateReflection(currentUser.id(servletRequest), recordId, request);
    }

    @GetMapping("/summary")
    public AchievementDtos.AchievementSummary summary(HttpServletRequest request) {
        return achievementService.summary(currentUser.id(request));
    }

    @GetMapping("/growth-tags")
    public List<AchievementDtos.GrowthTagResponse> growthTags(HttpServletRequest request) {
        return growthTagService.tags(currentUser.id(request));
    }

    @GetMapping("/growth-tags/{tagId}")
    public AchievementDtos.GrowthTagDetailResponse growthTagDetail(@PathVariable Long tagId,
                                                                   HttpServletRequest request) {
        return growthTagService.tagDetail(currentUser.id(request), tagId);
    }

    @PostMapping("/growth-tags/rebuild")
    public AchievementDtos.GrowthTagRebuildResponse rebuildGrowthTags(HttpServletRequest request) {
        return growthTagService.rebuild(currentUser.id(request));
    }

    @PutMapping("/growth-tags/evidences/{evidenceId}/milestone")
    public AchievementDtos.GrowthTagEvidenceResponse markGrowthMilestone(@PathVariable Long evidenceId,
                                                                         @Valid @RequestBody AchievementDtos.MilestoneRequest request,
                                                                         HttpServletRequest servletRequest) {
        return growthTagService.markMilestone(currentUser.id(servletRequest), evidenceId, request);
    }
}
