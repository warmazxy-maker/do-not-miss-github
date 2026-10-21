package com.donotmiss.backend.capabilityv2;

/**
 * The four business origins that may contribute material to the capability system.
 *
 * <p>This is deliberately more concrete than the old evidence source type. For example,
 * an activity completion and a resume interview can both be credible evidence, but they
 * arrive through different business flows and therefore need different adapters.</p>
 */
public enum CapabilityEvidenceSourceKind {
    ACTIVITY_COMPLETION,
    CHALLENGE_COMPLETION,
    COACH_LOG,
    RESUME_INTERVIEW
}
