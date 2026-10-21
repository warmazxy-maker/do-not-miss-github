package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only Stage 13 endpoint. Formal migration deliberately has no endpoint yet. */
@RestController
@RequestMapping("/api/capability-v2")
public class CapabilityV2MigrationPreviewController {
    private final LegacyAbilityMigrationPreviewService migrationPreviewService;
    private final CurrentUser currentUser;

    public CapabilityV2MigrationPreviewController(LegacyAbilityMigrationPreviewService migrationPreviewService,
                                                  CurrentUser currentUser) {
        this.migrationPreviewService = migrationPreviewService;
        this.currentUser = currentUser;
    }

    @GetMapping("/migration-preview")
    public LegacyAbilityMigrationPreviewService.MigrationPreview migrationPreview(HttpServletRequest request) {
        return migrationPreviewService.previewForUser(currentUser.id(request));
    }
}
