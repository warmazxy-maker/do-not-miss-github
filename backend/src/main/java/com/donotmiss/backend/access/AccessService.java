package com.donotmiss.backend.access;

import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is calling and what their role allows. User → role is read per request (one indexed lookup,
 * so a role change applies on the next request); role → permissions is cached briefly because it
 * changes only with a migration.
 */
@Service
public class AccessService {
    private static final Logger log = LoggerFactory.getLogger(AccessService.class);
    static final Duration PERMISSION_CACHE_TTL = Duration.ofSeconds(60);

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Map<String, CachedPermissions> cache = new ConcurrentHashMap<>();

    public AccessService(CurrentUser currentUser, UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this(currentUser, userRepository, jdbcTemplate, Clock.systemUTC());
    }

    AccessService(CurrentUser currentUser, UserRepository userRepository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** The logged-in caller; 401 when there is no valid session. */
    public Principal current(HttpServletRequest request) {
        String userId = currentUser.id(request);
        UserEntity user = userRepository.findByPublicUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户不存在或登录已失效"));
        return new Principal(userId, user.getRole(), permissionsOf(user.getRole()));
    }

    /** Never throws: anonymous or unknown callers simply have no permissions. */
    public boolean has(HttpServletRequest request, Permission permission) {
        try {
            return current(request).has(permission);
        } catch (RuntimeException notLoggedIn) {
            return false;
        }
    }

    public Set<Permission> permissionsOf(String roleCode) {
        if (roleCode == null) {
            return Set.of();
        }
        Instant now = clock.instant();
        CachedPermissions cached = cache.get(roleCode);
        if (cached != null && cached.loadedAt().plus(PERMISSION_CACHE_TTL).isAfter(now)) {
            return cached.permissions();
        }
        Set<Permission> loaded = load(roleCode);
        cache.put(roleCode, new CachedPermissions(loaded, now));
        return loaded;
    }

    private Set<Permission> load(String roleCode) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String name : jdbcTemplate.queryForList(
                "SELECT permission FROM role_permissions WHERE role_code = ?", String.class, roleCode)) {
            // A permission removed from the code but still in the table is ignored, not fatal.
            Arrays.stream(Permission.values()).filter(item -> item.name().equals(name)).findFirst()
                    .ifPresentOrElse(permissions::add,
                            () -> log.warn("Unknown permission '{}' for role {} ignored.", name, roleCode));
        }
        return Set.copyOf(permissions);
    }

    public record Principal(String userId, String roleCode, Set<Permission> permissions) {
        public boolean has(Permission permission) {
            return permissions.contains(permission);
        }

        public boolean hasAny(Permission... wanted) {
            return Arrays.stream(wanted).anyMatch(permissions::contains);
        }
    }

    private record CachedPermissions(Set<Permission> permissions, Instant loadedAt) {
    }
}
