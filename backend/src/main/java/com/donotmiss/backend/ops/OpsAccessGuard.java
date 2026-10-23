package com.donotmiss.backend.ops;

import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gate for the operations console. It shows every user's agent runs, messages and records, so it is
 * off unless {@code app.ops.enabled=true} and the caller's public user id is in {@code app.ops.admin-users}.
 * Refusals are 404, so the console's existence is not revealed to other users.
 */
@Component
public class OpsAccessGuard {
    private final CurrentUser currentUser;
    private final boolean enabled;
    private final Set<String> adminUsers;

    public OpsAccessGuard(CurrentUser currentUser,
                          @Value("${app.ops.enabled:false}") boolean enabled,
                          @Value("${app.ops.admin-users:}") String adminUsers) {
        this.currentUser = currentUser;
        this.enabled = enabled;
        this.adminUsers = Arrays.stream(adminUsers.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowed(HttpServletRequest request) {
        if (!enabled || adminUsers.isEmpty()) {
            return false;
        }
        try {
            String userId = currentUser.id(request);
            return userId != null && adminUsers.contains(userId);
        } catch (RuntimeException notLoggedIn) {
            return false;
        }
    }

    public void require(HttpServletRequest request) {
        if (!allowed(request)) {
            throw ApiException.notFound("页面不存在");
        }
    }
}
