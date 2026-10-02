package com.verso.platform.observability.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.platform.observability.tracing.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/** The starter is loaded through AutoConfiguration.imports and registers the filter only in servlet apps. */
class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void traceIdFilter_whenServletApplication_runsAfterObservationAndBeforeSecurity() {
        web.run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean("traceIdFilterRegistration", FilterRegistrationBean.class);
            // After ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1) so an active span's trace id is reused,
            // before Spring Security's default filter order (-100) so security rejections carry X-Trace-Id.
            assertThat(registration.getOrder())
                    .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1)
                    .isLessThan(-100);
        });
    }

    @Test
    void traceIdFilter_whenServletApplication_coversEveryPath() {
        web.run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean("traceIdFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(TraceIdFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
        });
    }

    @Test
    void traceIdFilter_whenApplicationDefinesItsOwnRegistration_backsOff() {
        FilterRegistrationBean<TraceIdFilter> custom = new FilterRegistrationBean<>(new TraceIdFilter());
        web.withBean("traceIdFilterRegistration", FilterRegistrationBean.class, () -> custom)
                .run(context -> assertThat(context.getBean("traceIdFilterRegistration")).isSameAs(custom));
    }

    @Test
    void traceIdFilter_whenNotAWebApplication_isNotRegistered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean("traceIdFilterRegistration"));
    }
}
