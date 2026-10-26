package com.donotmiss.backend.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller's role must have <em>any</em> of these permissions. On a class it applies to every
 * method; a method-level annotation overrides the class-level one. Enforced by {@link PermissionAspect}.
 *
 * <p>Endpoints without the annotation keep today's behaviour (login only where the controller asks
 * for it). That is the agreed default while the project is not public; switching to deny-by-default
 * means annotating the remaining controllers.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    Permission[] value();

    /** Name in the audit log; defaults to Controller.method. */
    String action() default "";
}
