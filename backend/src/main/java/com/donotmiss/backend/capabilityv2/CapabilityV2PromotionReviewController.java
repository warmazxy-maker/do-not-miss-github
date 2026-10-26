package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.abilityscore.JudgeDtos;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Promotion review: the user explains a held promotion and the answers unlock the rank. */
@RestController
@RequirePermission(Permission.STUDENT_FEATURES)
@RequestMapping("/api/capability-v2")
public class CapabilityV2PromotionReviewController {
    private final CapabilityV2PromotionReviewService reviewService;
    private final CurrentUser currentUser;

    public CapabilityV2PromotionReviewController(CapabilityV2PromotionReviewService reviewService,
                                                 CurrentUser currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    @PostMapping("/buckets/{bucketId}/promotion-review")
    public CapabilityPromotionReviews.View start(@PathVariable Long bucketId, HttpServletRequest request) {
        return reviewService.start(currentUser.id(request), bucketId);
    }

    @PostMapping("/promotion-reviews/{reviewId}/submit")
    public CapabilityPromotionReviews.View submit(@PathVariable Long reviewId,
                                                  @Valid @RequestBody JudgeDtos.SubmitRequest body,
                                                  HttpServletRequest request) {
        return reviewService.submit(currentUser.id(request), reviewId, body.answers());
    }
}
