package com.donotmiss.backend.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UserProfileSnapshotRefreshJob {
    private static final Logger log = LoggerFactory.getLogger(UserProfileSnapshotRefreshJob.class);

    private final UserMemoryService userMemoryService;
    private final boolean enabled;
    private final int batchSize;

    public UserProfileSnapshotRefreshJob(UserMemoryService userMemoryService,
                                         @Value("${app.user-memory.snapshot-refresh-enabled:true}") boolean enabled,
                                         @Value("${app.user-memory.snapshot-refresh-batch-size:10}") int batchSize) {
        this.userMemoryService = userMemoryService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(
            initialDelayString = "${app.user-memory.snapshot-refresh-initial-delay-ms:60000}",
            fixedDelayString = "${app.user-memory.snapshot-refresh-delay-ms:300000}"
    )
    public void refreshDirtySnapshots() {
        if (!enabled) {
            return;
        }
        int refreshed = userMemoryService.refreshDirtyProfiles(batchSize);
        if (refreshed > 0) {
            log.info("Refreshed {} dirty user profile snapshots.", refreshed);
        }
    }
}
