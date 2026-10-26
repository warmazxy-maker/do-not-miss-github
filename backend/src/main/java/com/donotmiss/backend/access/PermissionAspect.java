package com.donotmiss.backend.access;

import com.donotmiss.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces {@link RequirePermission}. Spring wraps every annotated controller in a proxy; this
 * advice runs before the real method:
 * <ol>
 *     <li>resolve the caller (401 if not logged in),</li>
 *     <li>check the role has any of the required permissions (403 otherwise, always audited),</li>
 *     <li>run the method, auditing write operations under audited permissions.</li>
 * </ol>
 * Authorisation that depends on the data (e.g. "only the publisher of this event") cannot be
 * expressed by an annotation and stays in code, see {@code EventReviewAccess}.
 */
@Aspect
@Component
public class PermissionAspect {
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AccessService accessService;
    private final AuditLogService auditLogService;

    public PermissionAspect(AccessService accessService, AuditLogService auditLogService) {
        this.accessService = accessService;
        this.auditLogService = auditLogService;
    }

    @Around("@within(com.donotmiss.backend.access.RequirePermission) || @annotation(com.donotmiss.backend.access.RequirePermission)")
    public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequirePermission rule = resolve(method, joinPoint.getTarget().getClass());
        if (rule == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = currentRequest();
        String action = rule.action().isBlank()
                ? joinPoint.getTarget().getClass().getSimpleName() + "." + method.getName()
                : rule.action();
        String required = Arrays.stream(rule.value()).map(Enum::name).collect(Collectors.joining("|"));
        String httpMethod = request == null ? null : request.getMethod();
        String path = request == null ? null : request.getRequestURI();

        AccessService.Principal principal;
        try {
            principal = accessService.current(request);
        } catch (ApiException notLoggedIn) {
            audit(null, null, required, action, httpMethod, path, AuditLogEntity.Outcome.DENIED, "未登录");
            throw notLoggedIn;
        }
        if (!principal.hasAny(rule.value())) {
            audit(principal.userId(), principal.roleCode(), required, action, httpMethod, path,
                    AuditLogEntity.Outcome.DENIED, "角色缺少权限");
            throw new ApiException(HttpStatus.FORBIDDEN, "没有权限执行这个操作");
        }

        boolean audited = httpMethod != null && WRITE_METHODS.contains(httpMethod)
                && Arrays.stream(rule.value()).anyMatch(Permission::audited);
        try {
            Object result = joinPoint.proceed();
            if (audited) {
                audit(principal.userId(), principal.roleCode(), required, action, httpMethod, path,
                        AuditLogEntity.Outcome.ALLOWED, null);
            }
            return result;
        } catch (Throwable ex) {
            if (audited) {
                audit(principal.userId(), principal.roleCode(), required, action, httpMethod, path,
                        AuditLogEntity.Outcome.FAILED, ex.getMessage());
            }
            throw ex;
        }
    }

    /** Method-level annotation wins over class-level. */
    static RequirePermission resolve(Method method, Class<?> targetClass) {
        RequirePermission onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequirePermission.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(targetClass, RequirePermission.class);
    }

    private void audit(String userId, String roleCode, String permission, String action, String httpMethod,
                       String path, AuditLogEntity.Outcome outcome, String detail) {
        try {
            auditLogService.record(AuditLogEntity.of(userId, roleCode, permission, action, httpMethod, path, outcome, detail));
        } catch (RuntimeException ignored) {
            // Already logged inside AuditLogService; the audited request must not fail because of it.
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
