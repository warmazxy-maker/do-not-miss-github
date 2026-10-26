package com.donotmiss.backend.access;

import com.donotmiss.backend.common.ApiException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionAspectTest {
    @Mock
    private AccessService accessService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature signature;

    /** Stand-in controller: class requires STUDENT_FEATURES, one method overrides it. */
    @RequirePermission(Permission.STUDENT_FEATURES)
    static class SampleController {
        public String studentThing() {
            return "ok";
        }

        @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.approve")
        public String approve() {
            return "approved";
        }
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void methodAnnotationOverridesClassAnnotation() throws Exception {
        Method approve = SampleController.class.getMethod("approve");
        Method student = SampleController.class.getMethod("studentThing");

        assertEquals(Permission.EVENT_REVIEW, PermissionAspect.resolve(approve, SampleController.class).value()[0]);
        assertEquals(Permission.STUDENT_FEATURES, PermissionAspect.resolve(student, SampleController.class).value()[0]);
    }

    @Test
    void missingPermissionIsRefusedAndAudited() throws Throwable {
        call("approve", "POST");
        when(accessService.current(any())).thenReturn(principal("demo-student", "STUDENT", Permission.STUDENT_FEATURES));

        ApiException error = assertThrows(ApiException.class, () -> aspect().enforce(joinPoint));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(joinPoint, never()).proceed();
        AuditLogEntity entry = audited();
        assertEquals("DENIED", entry.getOutcome());
        assertEquals("demo-student", entry.getUserId());
        assertEquals("event.approve", entry.getAction());
        assertEquals("EVENT_REVIEW", entry.getPermission());
    }

    @Test
    void anonymousCallerIsAuditedAndGets401() throws Throwable {
        call("approve", "POST");
        when(accessService.current(any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "请先登录"));

        ApiException error = assertThrows(ApiException.class, () -> aspect().enforce(joinPoint));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        assertEquals("DENIED", audited().getOutcome());
    }

    @Test
    void allowedWriteUnderAuditedPermissionIsRecorded() throws Throwable {
        call("approve", "POST");
        when(accessService.current(any())).thenReturn(principal("op-1", "OPERATOR", Permission.EVENT_REVIEW));
        when(joinPoint.proceed()).thenReturn("approved");

        assertEquals("approved", aspect().enforce(joinPoint));
        AuditLogEntity entry = audited();
        assertEquals("ALLOWED", entry.getOutcome());
        assertEquals("OPERATOR", entry.getRoleCode());
        assertEquals("/api/sample", entry.getPath());
    }

    @Test
    void failingWriteIsRecordedAsFailedAndStillThrows() throws Throwable {
        call("approve", "POST");
        when(accessService.current(any())).thenReturn(principal("op-1", "OPERATOR", Permission.EVENT_REVIEW));
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("event gone"));

        assertThrows(IllegalStateException.class, () -> aspect().enforce(joinPoint));
        AuditLogEntity entry = audited();
        assertEquals("FAILED", entry.getOutcome());
        assertEquals("event gone", entry.getDetail());
    }

    @Test
    void readsAndUnauditedPermissionsAreNotLogged() throws Throwable {
        call("approve", "GET");
        when(accessService.current(any())).thenReturn(principal("op-1", "OPERATOR", Permission.EVENT_REVIEW));
        when(joinPoint.proceed()).thenReturn("approved");
        aspect().enforce(joinPoint);

        call("studentThing", "POST");
        when(accessService.current(any())).thenReturn(principal("demo-student", "STUDENT", Permission.STUDENT_FEATURES));
        when(joinPoint.proceed()).thenReturn("ok");
        aspect().enforce(joinPoint);

        verify(auditLogService, never()).record(any());
    }

    @Test
    void auditFailureDoesNotBreakTheRequest() throws Throwable {
        call("approve", "POST");
        when(accessService.current(any())).thenReturn(principal("op-1", "OPERATOR", Permission.EVENT_REVIEW));
        when(joinPoint.proceed()).thenReturn("approved");
        org.mockito.Mockito.doThrow(new IllegalStateException("audit table down")).when(auditLogService).record(any());

        assertEquals("approved", aspect().enforce(joinPoint));
    }

    private void call(String methodName, String httpMethod) throws Exception {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod(methodName));
        when(joinPoint.getTarget()).thenReturn(new SampleController());
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/api/sample");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private AuditLogEntity audited() {
        ArgumentCaptor<AuditLogEntity> entry = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).record(entry.capture());
        return entry.getValue();
    }

    private PermissionAspect aspect() {
        return new PermissionAspect(accessService, auditLogService);
    }

    private static AccessService.Principal principal(String userId, String role, Permission... permissions) {
        return new AccessService.Principal(userId, role, Set.of(permissions));
    }
}
