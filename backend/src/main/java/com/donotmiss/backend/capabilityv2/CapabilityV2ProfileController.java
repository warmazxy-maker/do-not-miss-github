package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated portfolio read endpoints for the dual-label capability page. */
@RestController
@RequirePermission(Permission.STUDENT_FEATURES)
@RequestMapping("/api/capability-v2")
public class CapabilityV2ProfileController {
    private final CapabilityV2ProfileService profileService;
    private final CapabilityV2BucketDetailService bucketDetailService;
    private final CurrentUser currentUser;

    public CapabilityV2ProfileController(CapabilityV2ProfileService profileService,
                                         CapabilityV2BucketDetailService bucketDetailService,
                                         CurrentUser currentUser) {
        this.profileService = profileService;
        this.bucketDetailService = bucketDetailService;
        this.currentUser = currentUser;
    }

    @GetMapping("/profile")
    public CapabilityV2ProfileService.CapabilityProfile profile(HttpServletRequest request) {
        return profileService.profileForUser(currentUser.id(request));
    }

    @GetMapping("/buckets/{bucketId}")
    public CapabilityV2BucketDetailService.BucketDetail bucketDetail(@PathVariable Long bucketId,
                                                                     HttpServletRequest request) {
        return bucketDetailService.detailForUser(currentUser.id(request), bucketId);
    }
}
