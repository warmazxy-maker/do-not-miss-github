package com.donotmiss.backend.resumeinterview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeSnapshotRepository extends JpaRepository<ResumeSnapshotEntity, Long> {
    List<ResumeSnapshotEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    Optional<ResumeSnapshotEntity> findByIdAndSessionId(Long id, Long sessionId);
}
