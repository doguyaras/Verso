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

    @Test
    void endpointWord_whenDerivedFromTheController_isAFixedUpperSnakeWord() {
        assertThat(AccessDeniedAuditListener.endpointWord(AdminMetricsController.class)).isEqualTo("ADMIN_METRICS");
        assertThat(AccessDeniedAuditListener.endpointWord(AdminRootController.class)).isEqualTo("ADMIN_ROOT");
    }
}
