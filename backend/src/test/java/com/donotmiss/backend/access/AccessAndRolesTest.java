package com.donotmiss.backend.access;

import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessAndRolesTest {
    private static final String PERMISSIONS_SQL = "SELECT permission FROM role_permissions WHERE role_code = ?";

    @Mock
    private CurrentUser currentUser;
    @Mock
    private UserRepository userRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private HttpServletRequest request;

    // ---- role -> permissions ----

    @Test
    void permissionsComeFromTheRoleAndUnknownOnesAreIgnored() {
        when(jdbcTemplate.queryForList(PERMISSIONS_SQL, String.class, "ADMIN"))
                .thenReturn(List.of("OPS_CONSOLE_VIEW", "OPS_ACTION", "REMOVED_LONG_AGO"));

        Set<Permission> permissions = service(Clock.systemUTC()).permissionsOf("ADMIN");

        assertEquals(Set.of(Permission.OPS_CONSOLE_VIEW, Permission.OPS_ACTION), permissions);
    }

    @Test
    void rolePermissionsAreCachedForAMinute() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T00:00:00Z"));
        AccessService service = service(clock);
        when(jdbcTemplate.queryForList(PERMISSIONS_SQL, String.class, "STUDENT")).thenReturn(List.of("STUDENT_FEATURES"));

        service.permissionsOf("STUDENT");
        clock.advanceSeconds(30);
        service.permissionsOf("STUDENT");
        verify(jdbcTemplate, times(1)).queryForList(PERMISSIONS_SQL, String.class, "STUDENT");

        clock.advanceSeconds(31);
        service.permissionsOf("STUDENT");
        verify(jdbcTemplate, times(2)).queryForList(PERMISSIONS_SQL, String.class, "STUDENT");
    }

    @Test
    void userRoleIsReadEveryRequestSoARoleChangeAppliesImmediately() {
        UserEntity user = new UserEntity();
        user.setRole("OPERATOR");
        when(currentUser.id(request)).thenReturn("op-1");
        when(userRepository.findByPublicUserId("op-1")).thenReturn(Optional.of(user));
        when(jdbcTemplate.queryForList(PERMISSIONS_SQL, String.class, "OPERATOR")).thenReturn(List.of("EVENT_REVIEW"));

        AccessService service = service(Clock.systemUTC());
        assertTrue(service.has(request, Permission.EVENT_REVIEW));
        assertFalse(service.has(request, Permission.OPS_CONSOLE_VIEW));
    }

    @Test
    void anonymousCallerHasNothing() {
        when(currentUser.id(request)).thenThrow(new RuntimeException("not logged in"));

        assertFalse(service(Clock.systemUTC()).has(request, Permission.STUDENT_FEATURES));
    }

    // ---- admin_zero bootstrap ----

    @Test
    void adminZeroIsCreatedFromConfiguredPasswordWithHashedPassword() {
        when(userRepository.findByUsername("admin_zero")).thenReturn(Optional.empty());

        new AdminZeroBootstrap(userRepository, "a-long-local-secret", "admin_zero@do-not-miss.local").run(null);

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(saved.capture());
        assertEquals("ADMIN_ZERO", saved.getValue().getRole());
        assertEquals("admin-zero", saved.getValue().getPublicUserId());
        assertNotEquals("a-long-local-secret", saved.getValue().getPasswordHash());
        assertTrue(saved.getValue().getPasswordHash().startsWith("$2"), "BCrypt hash expected");
    }

    @Test
    void noPasswordOrAWeakOneCreatesNothing() {
        when(userRepository.findByUsername("admin_zero")).thenReturn(Optional.empty());

        new AdminZeroBootstrap(userRepository, "", "x@y").run(null);
        new AdminZeroBootstrap(userRepository, "short", "x@y").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void existingAdminZeroKeepsItsPasswordButGetsItsRoleBack() {
        UserEntity existing = new UserEntity();
        existing.setRole("STUDENT");
        existing.setPasswordHash("$2a$existing");
        when(userRepository.findByUsername("admin_zero")).thenReturn(Optional.of(existing));

        new AdminZeroBootstrap(userRepository, "another-long-secret", "x@y").run(null);

        assertEquals("ADMIN_ZERO", existing.getRole());
        assertEquals("$2a$existing", existing.getPasswordHash());
        verify(userRepository, never()).save(any());
    }

    private AccessService service(Clock clock) {
        return new AccessService(currentUser, userRepository, jdbcTemplate, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
