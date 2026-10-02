package com.verso.platform.core.config;

import com.verso.platform.core.handler.ContainerErrorLogSilencer;
import com.verso.platform.core.handler.EnvelopeErrorController;
import com.verso.platform.core.handler.GlobalServiceExceptionHandler;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Starter entry point (reference 4.5): loaded through AutoConfiguration.imports, never by component scan, so
 * services do not need scanBasePackages tricks. Runs before Boot's ErrorMvcAutoConfiguration so the envelope error
 * controller replaces BasicErrorController (both are guarded by a missing ErrorController bean).
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
public class PlatformCoreAutoConfiguration {

    /** Time comes from one source: production uses UTC system time, tests inject a fixed or advancing Clock. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        GlobalServiceExceptionHandler globalServiceExceptionHandler(Clock clock) {
            return new GlobalServiceExceptionHandler(clock);
        }

        @Bean
        @ConditionalOnMissingBean(ErrorController.class)
        EnvelopeErrorController envelopeErrorController(Clock clock) {
            return new EnvelopeErrorController(clock);
        }

    }

    /** Separate class: its condition is checked before any Tomcat type is loaded (Tomcat is optional here). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.boot.tomcat.TomcatWebServer")
    static class TomcatConfiguration {

        /** Static: the listener exists before the web server starts. The logging system is one per JVM. */
        @Bean
        static ContainerErrorLogSilencer containerErrorLogSilencer() {
            return new ContainerErrorLogSilencer(LoggingSystem.get(PlatformCoreAutoConfiguration.class.getClassLoader()));
        }
    }
}
