package com.verso.platform.observability.config;

import com.verso.platform.observability.tracing.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Starter entry point (reference 4.5): loaded through AutoConfiguration.imports, never by component scan.
 *
 * <p>Filter order: after the observation filter (HIGHEST_PRECEDENCE + 1) so an active span's trace id can be
 * reused, and before Spring Security (-100) so security rejections also carry X-Trace-Id.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ObservabilityAutoConfiguration {

    static final int TRACE_ID_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    @Bean
    @ConditionalOnMissingBean(name = "traceIdFilterRegistration")
    FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(TRACE_ID_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
