package com.verso.audit.service;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditOutcome;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.platform.security.authority.VersoRoles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in-relevant denials (ADR-0026): an authenticated caller without the role asked an admin endpoint
 * ({@code @PreAuthorize(VersoRoles.HAS_ADMIN)}) and got 403. These are the probes a security reviewer wants to see.
 * Only controllers mapped under {@code /v1/admin}, or controller endpoints that declare the admin rule themselves
 * (ADR-0028), count: other method-security denials (a catalog method called from
 * a non-admin context) are programming errors, not caller behaviour. 401 (no or bad token) is not recorded: there is
 * no verified actor to attribute it to, and the identity provider holds the sign-in log.
 *
 * <p>The row carries the endpoint as a fixed word derived from the controller's class name (ADMIN_METRICS), never the
 * request path or its parameters.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AccessDeniedAuditListener {

    private static final String ADMIN_PREFIX = "/v1/admin";

    private final AuditRecorder recorder;

    public AccessDeniedAuditListener(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @EventListener
    public void on(AuthorizationDeniedEvent<?> denied) {
        if (!(denied.getObject() instanceof MethodInvocation invocation)) return;
        Class<?> controller = invocation.getMethod().getDeclaringClass();
        if (!isAdminController(controller) && !declaresAdmin(invocation.getMethod(), controller)) return;
        Authentication authentication = denied.getAuthentication().get();
        recorder.record(new AuditEvent(AuditAction.ACCESS_DENIED, AuditOutcome.DENIED, AuditTargetType.ADMIN_API, null,
                null, endpointWord(controller), null, null, List.of()), authentication);
    }

    static boolean isAdminController(Class<?> type) {
        if (!AnnotatedElementUtils.hasAnnotation(type, RestController.class)) return false;
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        if (mapping == null) return false;
        for (String path : mapping.path()) {
            if (path.equals(ADMIN_PREFIX) || path.startsWith(ADMIN_PREFIX + "/")) return true;
        }
        return false;
    }

    /**
     * A controller endpoint outside /v1/admin that declares {@code @PreAuthorize(VersoRoles.HAS_ADMIN)} on the method or
     * the class (ADR-0028: collection management under /v1/collections, collection upload and move under
     * /v1/documents). Only controllers: a denied service method stays a programming error, as above.
     */
    static boolean declaresAdmin(Method method, Class<?> type) {
        if (!AnnotatedElementUtils.hasAnnotation(type, RestController.class)) return false;
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (rule == null) rule = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
        return rule != null && VersoRoles.HAS_ADMIN.equals(rule.value());
    }

    /** AdminMetricsController becomes ADMIN_METRICS. */
    static String endpointWord(Class<?> controller) {
        String name = controller.getSimpleName().replaceAll("Controller$", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        return name.matches("[A-Z0-9_]{1,40}") ? name : "ADMIN_API";
    }
}
