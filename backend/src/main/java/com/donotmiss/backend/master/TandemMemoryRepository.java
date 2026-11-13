package com.donotmiss.backend.master;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TandemMemoryRepository extends JpaRepository<TandemMemoryEntity, Long> {
    List<TandemMemoryEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<TandemMemoryEntity> findByIdAndUserId(Long id, String userId);

    long countByUserId(String userId);
}
