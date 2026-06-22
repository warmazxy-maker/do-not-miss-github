package com.donotmiss.backend.event;

import com.donotmiss.backend.common.ApiException;

public enum EventCategory {
    PUBLIC_WELFARE("公益"),
    COMPANY("企业"),
    CAMPUS("校内"),
    ONLINE("线上"),
    RESEARCH("研究"),
    CULTURE("文化");

    private final String label;

    EventCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static EventCategory fromText(String text) {
        for (EventCategory category : values()) {
            if (category.name().equalsIgnoreCase(text) || category.label.equals(text)) {
                return category;
            }
        }
        throw ApiException.badRequest("未知事件分类：" + text);
    }
}
