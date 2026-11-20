package com.donotmiss.backend.master;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TandemMessageRepository extends JpaRepository<TandemMessageEntity, Long> {
    /** Newest first; callers reverse for display. */
    List<TandemMessageEntity> findByUserIdOrderByIdDesc(String userId, Pageable page);

    List<TandemMessageEntity> findByUserIdAndIdLessThanOrderByIdDesc(String userId, Long beforeId, Pageable page);

    List<TandemMessageEntity> findByUserIdAndIdGreaterThanOrderByIdAsc(String userId, Long afterId);

    List<TandemMessageEntity> findByUserIdOrderByIdAsc(String userId);

    /** F4: proactive messages the student has not seen yet. */
    long countByUserIdAndProactiveTrueAndReadAtIsNull(String userId);

    /** F4: for the daily limit. */
    long countByUserIdAndProactiveTrueAndCreatedAtGreaterThanEqual(String userId, java.time.Instant since);

    @Modifying
    @Query("update TandemMessageEntity m set m.readAt = ?2 where m.userId = ?1 and m.proactive = true and m.readAt is null")
    int markProactiveRead(String userId, java.time.Instant now);

    @Modifying
    @Query("delete from TandemMessageEntity m where m.userId = ?1")
    int deleteAllOfUser(String userId);
}
