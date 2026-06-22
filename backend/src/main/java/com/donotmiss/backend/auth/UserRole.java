package com.donotmiss.backend.auth;

import com.donotmiss.backend.common.ApiException;

public enum UserRole {
    STUDENT,
    SOCIAL;

    public static UserRole fromText(String text) {
        if (text == null || text.isBlank()) {
            return STUDENT;
        }

        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(text)) {
                return role;
            }
        }

        throw ApiException.badRequest("未知用户角色：" + text);
    }
}
