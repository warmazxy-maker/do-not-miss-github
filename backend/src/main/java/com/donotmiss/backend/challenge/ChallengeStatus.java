package com.donotmiss.backend.challenge;

import com.donotmiss.backend.common.ApiException;

public enum ChallengeStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED;

    public static ChallengeStatus fromText(String text) {
        for (ChallengeStatus status : values()) {
            if (status.name().equalsIgnoreCase(text)) {
                return status;
            }
        }
        throw ApiException.badRequest("未知挑战状态：" + text);
    }
}
