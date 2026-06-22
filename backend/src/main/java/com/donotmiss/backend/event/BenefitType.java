package com.donotmiss.backend.event;

import com.donotmiss.backend.common.ApiException;

public enum BenefitType {
    SKILL("技能经验"),
    MONEY("金钱报酬"),
    BOTH("两者都有");

    private final String label;

    BenefitType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean hasSkill() {
        return this == SKILL || this == BOTH;
    }

    public boolean hasMoney() {
        return this == MONEY || this == BOTH;
    }

    public static BenefitType fromText(String text) {
        for (BenefitType type : values()) {
            if (type.name().equalsIgnoreCase(text) || type.label.equals(text)) {
                return type;
            }
        }
        throw ApiException.badRequest("未知收益类型：" + text);
    }
}
