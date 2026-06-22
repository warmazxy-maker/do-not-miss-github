package com.donotmiss.backend.common;

import com.donotmiss.backend.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 当前用户解析器。
 * 注册/登录后，前端需要在请求头中带：
 * Authorization: Bearer <token>
 */
@Component
public class CurrentUser {
    private final AuthService authService;

    public CurrentUser(AuthService authService) {
        this.authService = authService;
    }

    public String id(HttpServletRequest request) {
        return authService.resolveUserId(token(request));
    }

    public String token(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        return authorization.substring("Bearer ".length()).trim();
    }
}
