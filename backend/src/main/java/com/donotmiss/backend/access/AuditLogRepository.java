package com.donotmiss.backend.access;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    @Query("""
            select entry from AuditLogEntity entry
            where (:outcome is null or entry.outcome = :outcome)
              and (:userId is null or entry.userId = :userId)
            order by entry.id desc
            """)
    List<AuditLogEntity> search(@Param("outcome") String outcome, @Param("userId") String userId, Pageable pageable);
}
