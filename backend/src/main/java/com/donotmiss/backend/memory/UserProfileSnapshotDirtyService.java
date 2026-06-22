package com.donotmiss.backend.memory;

import com.donotmiss.backend.mq.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileSnapshotDirtyService {
    private final UserProfileSnapshotRepository snapshotRepository;
    private final DomainEventPublisher domainEventPublisher;

    public UserProfileSnapshotDirtyService(UserProfileSnapshotRepository snapshotRepository,
                                           DomainEventPublisher domainEventPublisher) {
        this.snapshotRepository = snapshotRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Transactional
    public void markDirty(String userId) {
        markDirty(userId, "profile-source-updated");
    }

    @Transactional
    public void markDirty(String userId, String reason) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String normalizedUserId = userId.trim();
        UserProfileSnapshotEntity snapshot = snapshotRepository.findByUserId(normalizedUserId)
                .orElseGet(UserProfileSnapshotEntity::new);
        snapshot.setUserId(normalizedUserId);
        snapshot.setDirty(true);
        snapshot.setGeneratedBy("dirty");
        snapshotRepository.save(snapshot);
        domainEventPublisher.publishUserProfileRefresh(normalizedUserId, reason);
    }
}
