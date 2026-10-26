package com.donotmiss.backend.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes the audit trail. Own transaction, failures swallowed: a refused request must still be
 * recorded even though the request itself fails, and a broken audit table must not take the
 * audited operation down with it (same rule as recommendation feedback in B1).
 */
@Service
public class AuditLogService {
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLogEntity entry) {
        try {
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Could not write audit log. action={}, outcome={}, reason={}",
                    entry.getAction(), entry.getOutcome(), ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AuditLogEntity> search(String outcome, String userId, int limit) {
        return repository.search(blankToNull(outcome), blankToNull(userId), PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
