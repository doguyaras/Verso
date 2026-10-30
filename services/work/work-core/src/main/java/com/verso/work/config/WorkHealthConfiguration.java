package com.verso.work.config;

import com.verso.work.repository.WorkDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "workDb" in the readiness group (application.yml, ADR-0057): a wrong svc_work password or a missing "work" schema
 * must not leave the container healthy while every skill run and the run history answer 503 (bug hunt 2026-10-30; the
 * same rule as qaDb). Registered in every application type, because the group is validated at start-up; without a web
 * server there is no work pool and no run to lose, so it reports UP there. The failure detail is the exception type
 * only.
 */
@Configuration(proxyBeanMethods = false)
public class WorkHealthConfiguration {

    @Bean
    HealthIndicator workDb(ObjectProvider<WorkDatabase> database) {
        return () -> {
            WorkDatabase work = database.getIfAvailable();
            if (work == null) return Health.up().build();
            try {
                work.ping();
                return Health.up().build();
            } catch (RuntimeException e) {
                return Health.down().withDetail("exceptionType", e.getClass().getSimpleName()).build();
            }
        };
    }
}
