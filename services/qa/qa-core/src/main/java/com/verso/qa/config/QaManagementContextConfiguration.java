package com.verso.qa.config;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The management server (its own port, a child context) gets the X-Rag-Mode header too (llm-rules 1.3: every response;
 * phase 5 review P1/L7). Registered in META-INF/spring/...ManagementContextConfiguration.imports; on a shared port the
 * main context's filter already covers it.
 */
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaManagementContextConfiguration {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> managementRagModeHeaderFilter(VersoAiProperties ai) {
        return QaConfiguration.ragModeHeaderRegistration(ai, "managementRagModeHeaderFilter");
    }
}
