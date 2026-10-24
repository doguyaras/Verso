package com.verso.audit.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;

/**
 * Method security publishes an {@code AuthorizationDeniedEvent} when a {@code @PreAuthorize} refuses a caller; the
 * audit module records those that hit an admin endpoint ({@code AccessDeniedAuditListener}). The platform's security
 * chain is not touched (it is the last catch-all, ADR-0010): a bean of this type is picked up by method security.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditSecurityConfiguration {

    @Bean
    AuthorizationEventPublisher auditAuthorizationEventPublisher(ApplicationEventPublisher publisher) {
        return new SpringAuthorizationEventPublisher(publisher);
    }
}
