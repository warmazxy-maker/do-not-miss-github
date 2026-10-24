package com.donotmiss.backend.recommendation;

/**
 * PENDING: waiting for an operator. APPROVED: will be included in the next exported evaluation set
 * version. REJECTED: not a useful case (noise, duplicate, unclear need).
 */
public enum EvalCandidateStatus {
    PENDING,
    APPROVED,
    REJECTED
}
