package com.donotmiss.backend.access;

/**
 * What a role may do. Defined in code because code checks them: a new permission only appears
 * together with the feature that needs it. Which role has which permission is data (role_permissions).
 *
 * <p>{@code audited}: successful write operations under this permission are written to the audit
 * log. Refused attempts are always audited, whatever the permission.</p>
 */
public enum Permission {
    STUDENT_FEATURES(false),
    EVENT_PUBLISH(true),
    EVENT_REVIEW(true),
    EVAL_CANDIDATE_REVIEW(true),
    OPS_CONSOLE_VIEW(false),
    OPS_ACTION(true),
    AUDIT_VIEW(false),
    ROLE_ASSIGN(true);

    private final boolean audited;

    Permission(boolean audited) {
        this.audited = audited;
    }

    public boolean audited() {
        return audited;
    }
}
