package com.donotmiss.backend.achievement;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequirePermission(Permission.STUDENT_FEATURES)
@RequestMapping("/api/achievements")
public class AchievementController {
    private final AchievementService achievementService;
    private final CurrentUser currentUser;

    public AchievementController(AchievementService achievementService,
                                 CurrentUser currentUser) {
        this.achievementService = achievementService;
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
}
