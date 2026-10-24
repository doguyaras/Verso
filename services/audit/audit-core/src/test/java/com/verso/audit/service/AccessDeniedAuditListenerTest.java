package com.verso.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which denials are audited and how an endpoint becomes a fixed word (ADR-0026). */
class AccessDeniedAuditListenerTest {

    @RestController
    @RequestMapping("/v1/admin/metrics")
    static class AdminMetricsController {
    }

    @RestController
    @RequestMapping("/v1/admin")
    static class AdminRootController {
    }

    @RestController
    @RequestMapping("/v1/administrators")
    static class LookalikeController {
    }

    @RestController
    @RequestMapping("/v1/documents")
    static class DocumentController {
    }

    static class NotAController {
    }

    @Test
    void isAdminController_whenMappedUnderV1Admin_isTrueAndOnlyThen() {
        assertThat(AccessDeniedAuditListener.isAdminController(AdminMetricsController.class)).isTrue();
        assertThat(AccessDeniedAuditListener.isAdminController(AdminRootController.class)).isTrue();
        assertThat(AccessDeniedAuditListener.isAdminController(LookalikeController.class)).isFalse();
        assertThat(AccessDeniedAuditListener.isAdminController(DocumentController.class)).isFalse();
        assertThat(AccessDeniedAuditListener.isAdminController(NotAController.class)).isFalse();
    }

    @RestController
    @RequestMapping("/v1/collections")
    @org.springframework.security.access.prepost.PreAuthorize(com.verso.platform.security.authority.VersoRoles.HAS_ADMIN)
    static class CollectionAdminController {
        void create() {
        }
    }

    static class DocumentEndpoints {
        @org.springframework.security.access.prepost.PreAuthorize(com.verso.platform.security.authority.VersoRoles.HAS_ADMIN)
        void move() {
        }

        @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
        void list() {
        }
    }

    /** ADR-0028: admin endpoints outside /v1/admin that declare the rule themselves, on the class or the method. */
    @Test
    void declaresAdmin_whenTheControllerOrMethodRequiresVersoAdmin_isTrueAndOnlyThen() throws Exception {
        assertThat(AccessDeniedAuditListener.declaresAdmin(CollectionAdminController.class.getDeclaredMethod("create"),
                CollectionAdminController.class)).isTrue();
        assertThat(AccessDeniedAuditListener.declaresAdmin(DocumentEndpoints.class.getDeclaredMethod("move"),
                DocumentController.class)).isTrue();
        assertThat(AccessDeniedAuditListener.declaresAdmin(DocumentEndpoints.class.getDeclaredMethod("list"),
                DocumentController.class)).as("another rule").isFalse();
        assertThat(AccessDeniedAuditListener.declaresAdmin(DocumentEndpoints.class.getDeclaredMethod("move"),
                NotAController.class)).as("a service method stays a programming error").isFalse();
        assertThat(AccessDeniedAuditListener.endpointWord(CollectionAdminController.class)).isEqualTo("COLLECTION_ADMIN");
    }

    @Test
    void endpointWord_whenDerivedFromTheController_isAFixedUpperSnakeWord() {
        assertThat(AccessDeniedAuditListener.endpointWord(AdminMetricsController.class)).isEqualTo("ADMIN_METRICS");
        assertThat(AccessDeniedAuditListener.endpointWord(AdminRootController.class)).isEqualTo("ADMIN_ROOT");
    }
}
