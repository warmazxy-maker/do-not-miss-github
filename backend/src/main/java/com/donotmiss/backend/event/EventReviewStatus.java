package com.donotmiss.backend.event;

public enum EventReviewStatus {
    PENDING_REVIEW("待审核"),
    APPROVED("已通过"),
    NEEDS_REVISION("需修改"),
    REJECTED("已拒绝");

    private final String label;

    EventReviewStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
