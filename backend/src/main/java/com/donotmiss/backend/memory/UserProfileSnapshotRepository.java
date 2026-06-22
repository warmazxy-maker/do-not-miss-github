package com.donotmiss.backend.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserProfileSnapshotRepository extends JpaRepository<UserProfileSnapshotEntity, Long> {
    Optional<UserProfileSnapshotEntity> findByUserId(String userId);

    List<UserProfileSnapshotEntity> findTop20ByDirtyTrueOrderByUpdatedAtAsc();
}
