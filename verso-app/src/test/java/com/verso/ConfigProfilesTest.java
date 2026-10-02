package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The real config files resolve as documented: application.yml imports config/verso.yml in every environment,
 * deployments log ECS JSON, and the local profile keeps plain-text logs (reference 8.1, 15.1).
 */
class ConfigProfilesTest {

    @Configuration(proxyBeanMethods = false)
    static class Empty {}

    @Test
    void loggingFormat_whenDeployProfile_isEcsJson() {
        try (ConfigurableApplicationContext context = start("prod")) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
            assertThat(env.getProperty("management.server.port")).isEqualTo("8081");
        }
    }

    @Test
    void loggingFormat_whenLocalProfile_isPlainText() {
        try (ConfigurableApplicationContext context = start("local")) {
            assertThat(context.getEnvironment().getProperty("logging.structured.format.console")).isNull();
            assertThat(context.getEnvironment().getProperty("management.endpoint.health.show-details"))
                    .isEqualTo("always");
        }
    }

    private static ConfigurableApplicationContext start(String profile) {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles(profile);
        return app.run();
    }
}
