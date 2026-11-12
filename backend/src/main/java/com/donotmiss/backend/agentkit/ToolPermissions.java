package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.access.AccessService;
import com.donotmiss.backend.access.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * What the user a tool call is for may do. An interface so the executor can be tested without a
 * database; in the application it is the D1 role table.
 */
public interface ToolPermissions {
    Set<Permission> of(String userId);

    @Component
    class FromAccessService implements ToolPermissions {
        private final AccessService accessService;

        public FromAccessService(AccessService accessService) {
            this.accessService = accessService;
        }

        @Override
        public Set<Permission> of(String userId) {
            return accessService.permissionsOfUser(userId);
        }
    }
}
