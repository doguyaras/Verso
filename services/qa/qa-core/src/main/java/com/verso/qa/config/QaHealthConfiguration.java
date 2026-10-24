package com.verso.qa.config;

import com.verso.qa.repository.QaDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "qaDb" in the readiness group (application.yml, ADR-0023): a wrong svc_qa password or a missing "qa" schema must not
 * leave the container healthy while every answer silently skips the history (environment review). Registered in every
 * application type, because the group is validated at start-up; without a web server there is no qa pool and no answer
 * to lose, so it reports UP there. The failure detail is the exception type only.
 */
@Configuration(proxyBeanMethods = false)
public class QaHealthConfiguration {

    @Bean
    HealthIndicator qaDb(ObjectProvider<QaDatabase> database) {
        return () -> {
            QaDatabase qa = database.getIfAvailable();
            if (qa == null) return Health.up().build();
            try {
                qa.ping();
                return Health.up().build();
            } catch (RuntimeException e) {
                return Health.down().withDetail("exceptionType", e.getClass().getSimpleName()).build();
            }
        };
    }
}
