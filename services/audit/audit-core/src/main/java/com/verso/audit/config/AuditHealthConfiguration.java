package com.verso.audit.config;

import com.verso.audit.repository.AuditDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "auditDb" in the readiness group (application.yml, ADR-0026): a wrong svc_audit password or a missing "audit" schema
 * must not leave the container healthy while every audit record stays in the module outboxes and every admin audit
 * query answers 503 (bug hunt 2026-10-30; the same rule as qaDb). Registered in every application type, because the
 * group is validated at start-up; without a web server there is no audit pool and nothing to lose, so it reports UP
 * there. The failure detail is the exception type only.
 */
@Configuration(proxyBeanMethods = false)
public class AuditHealthConfiguration {

    @Bean
    HealthIndicator auditDb(ObjectProvider<AuditDatabase> database) {
        return () -> {
            AuditDatabase audit = database.getIfAvailable();
            if (audit == null) return Health.up().build();
            try {
                audit.ping();
                return Health.up().build();
            } catch (RuntimeException e) {
                return Health.down().withDetail("exceptionType", e.getClass().getSimpleName()).build();
            }
        };
    }
}
