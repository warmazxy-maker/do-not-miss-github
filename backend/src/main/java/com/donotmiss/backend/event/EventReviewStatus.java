package com.donotmiss.backend.event;

/**
 * Publication dimension of an event (D2). Each state has two labels: what the organisation sees
 * about its own event, and the reviewer's wording. Students never see these directly; they get
 * {@link EventStudentView}, which also folds in the time dimension.
 */
public enum EventReviewStatus {
    DRAFT("已保存"),
    PENDING_REVIEW("审核中"),
    NEEDS_REVISION("需修改"),
    APPROVED("已上架"),
    OFFLINE("已下架"),
    /** Kept for a future "cannot be resubmitted" verdict; nothing produces it today. */
    REJECTED("已拒绝");

    private final String label;

    EventReviewStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
