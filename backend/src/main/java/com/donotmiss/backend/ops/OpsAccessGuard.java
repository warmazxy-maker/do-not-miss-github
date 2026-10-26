package com.donotmiss.backend.ops;

import com.donotmiss.backend.access.AccessService;
import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gate for the operations console. Two independent layers (defence in depth):
 * <ol>
 *     <li>the master switch {@code app.ops.enabled}: closes the console for everyone without
 *     touching any role;</li>
 *     <li>the caller's role must have {@link Permission#OPS_CONSOLE_VIEW} (D1; replaced the
 *     {@code OPS_ADMIN_USERS} allow-list).</li>
 * </ol>
 * Refusals are 404, so the console's existence is not revealed to other users.
 */
@Component
public class OpsAccessGuard {
    private final AccessService accessService;
    private final boolean enabled;

    public OpsAccessGuard(AccessService accessService, @Value("${app.ops.enabled:false}") boolean enabled) {
        this.accessService = accessService;
        this.enabled = enabled;
    }

    public boolean allowed(HttpServletRequest request) {
        return enabled && accessService.has(request, Permission.OPS_CONSOLE_VIEW);
    }

    public void require(HttpServletRequest request) {
        if (!allowed(request)) {
            throw ApiException.notFound("页面不存在");
        }
    }

    /** Master switch only; the permission is checked by {@code @RequirePermission} on the endpoint. */
    public void requireEnabled() {
        if (!enabled) {
            throw ApiException.notFound("页面不存在");
        }
    }
}
