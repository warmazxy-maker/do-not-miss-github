package com.donotmiss.backend.agentkit.actions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PendingActionRepository extends JpaRepository<PendingActionEntity, Long> {
    Optional<PendingActionEntity> findByIdAndUserId(Long id, String userId);

    List<PendingActionEntity> findByUserIdAndRunIdInOrderByIdAsc(String userId, Collection<Long> runIds);

    /**
     * Compare-and-set on the status: changes it only if it is still {@code from}. Returns 1 for the
     * click that wins, 0 for every other one. The UPDATE locks the row until the transaction ends,
     * so a second click waits and then sees the new status.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PendingActionEntity a set a.status = :to, a.decidedAt = :now
            where a.id = :id and a.userId = :userId and a.status = :from
            """)
    int transition(@Param("id") Long id, @Param("userId") String userId,
                   @Param("from") PendingActionStatus from, @Param("to") PendingActionStatus to,
                   @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PendingActionEntity a set a.status = :superseded, a.decidedAt = :now, a.replacedBy = :newId
            where a.id = :oldId and a.userId = :userId and a.status = :pending
            """)
    int supersede(@Param("oldId") Long oldId, @Param("newId") Long newId, @Param("userId") String userId,
                  @Param("now") Instant now, @Param("pending") PendingActionStatus pending,
                  @Param("superseded") PendingActionStatus superseded);

    /** Rollback: DONE to UNDONE, same compare-and-set as {@link #transition}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PendingActionEntity a set a.status = :undone, a.undoneAt = :now
            where a.id = :id and a.userId = :userId and a.status = :done
            """)
    int markUndone(@Param("id") Long id, @Param("userId") String userId, @Param("now") Instant now,
                   @Param("done") PendingActionStatus done, @Param("undone") PendingActionStatus undone);

    /** Clearing the conversation voids its open cards (F2 decision 6). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PendingActionEntity a set a.status = :expired, a.decidedAt = :now
            where a.userId = :userId and a.agentName = :agentName and a.status = :pending
            """)
    int expireOpen(@Param("userId") String userId, @Param("agentName") String agentName, @Param("now") Instant now,
                   @Param("pending") PendingActionStatus pending, @Param("expired") PendingActionStatus expired);
}
