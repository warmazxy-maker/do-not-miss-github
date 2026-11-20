package com.donotmiss.backend.master.wake;

/**
 * What can wake Tandem (F4 decision 2). Reminders are fixed text and cost no model call
 * (decision 3); the others are written by the model, which may also decide to say nothing.
 */
public enum WakeKind {
    CHECK_IN("活动签到", false),
    GOAL_STEP("目标进展", false),
    RANK_UP("能力晋级", false),
    EVENT_TOMORROW("活动提醒", true),
    GOAL_REVIEW("回顾邀请", false);

    private final String label;
    private final boolean template;

    WakeKind(String label, boolean template) {
        this.label = label;
        this.template = template;
    }

    public String label() {
        return label;
    }

    public boolean template() {
        return template;
    }
}
