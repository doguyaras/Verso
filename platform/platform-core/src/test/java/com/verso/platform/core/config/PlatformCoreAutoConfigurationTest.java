package com.verso.platform.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.platform.core.handler.EnvelopeErrorController;
import com.verso.platform.core.handler.GlobalServiceExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.error.ErrorController;

class PlatformCoreAutoConfigurationTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformCoreAutoConfiguration.class));

    @Test
    void autoConfiguration_whenServletApplication_providesHandlerAndUtcClock() {
        web.run(context -> {
            assertThat(context).hasSingleBean(GlobalServiceExceptionHandler.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void clock_whenApplicationDefinesOne_backsOff() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformCoreAutoConfiguration.class))
                .withBean(Clock.class, () -> fixed)
                .run(context -> assertThat(context.getBean(Clock.class)).isSameAs(fixed));
    }

    @Test
    void handler_whenApplicationDefinesOne_backsOff() {
        GlobalServiceExceptionHandler custom = new GlobalServiceExceptionHandler(Clock.systemUTC());
        web.withBean(GlobalServiceExceptionHandler.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(GlobalServiceExceptionHandler.class);
                    assertThat(context.getBean(GlobalServiceExceptionHandler.class)).isSameAs(custom);
                });
    }

    @Test
    void errorController_whenServletApplication_isTheEnvelopeController() {
        web.run(context -> assertThat(context).hasSingleBean(EnvelopeErrorController.class));
    }

    /** Second-round review (R9b): an application error controller must win over the starter. */
    @Test
    void errorController_whenApplicationDefinesOne_backsOff() {
        ErrorController custom = new ErrorController() {};
        web.withBean(ErrorController.class, () -> custom)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EnvelopeErrorController.class);
                    assertThat(context.getBean(ErrorController.class)).isSameAs(custom);
                });
    }

    @Test
    void handler_whenNotAWebApplication_isNotRegistered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformCoreAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(GlobalServiceExceptionHandler.class));
    }
}
