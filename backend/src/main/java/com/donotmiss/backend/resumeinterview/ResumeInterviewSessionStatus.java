package com.donotmiss.backend.resumeinterview;

import java.util.Set;

public enum ResumeInterviewSessionStatus {
    CREATED,
    RESUME_PARSED,
    INTERVIEWING,
    EVIDENCE_READY,
    SCORED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean canMoveTo(ResumeInterviewSessionStatus next) {
        if (next == null || this == next) {
            return false;
        }
        if (this == COMPLETED || this == FAILED || this == CANCELLED) {
            return false;
        }
        return switch (this) {
            case CREATED -> Set.of(RESUME_PARSED, FAILED, CANCELLED).contains(next);
            case RESUME_PARSED -> Set.of(INTERVIEWING, EVIDENCE_READY, FAILED, CANCELLED).contains(next);
            case INTERVIEWING -> Set.of(EVIDENCE_READY, FAILED, CANCELLED).contains(next);
            case EVIDENCE_READY -> Set.of(SCORED, FAILED, CANCELLED).contains(next);
            case SCORED -> Set.of(COMPLETED, FAILED).contains(next);
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
