package com.donotmiss.backend.agentkit.actions;

/**
 * Life of a confirmation card. PENDING is the only state a click can confirm or cancel; DONE the
 * only one that can be rolled back. Every change is a compare-and-set on the status in the
 * database, so two clicks never both win (F2 decision 8).
 */
public enum PendingActionStatus {
    PENDING("等待确认"),
    DONE("已执行"),
    FAILED("执行失败"),
    CANCELLED("已取消"),
    EXPIRED("已过期"),
    SUPERSEDED("已被替换"),
    UNDONE("已撤销");

    private final String label;

    PendingActionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
