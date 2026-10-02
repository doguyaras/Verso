package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * The local profile points the IDE run at the compose PostgreSQL published by deploy/compose.local.yaml, with the
     * same two roles as production; the deploy profile keeps the placeholders that compose fills in.
     */
    @Test
    void database_whenLocalProfile_isLocalhostWithTheProductionRoles() {
        try (ConfigurableApplicationContext context = start("local")) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5432/verso?logServerErrorDetail=false");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("svc_document");
            assertThat(env.getProperty("spring.flyway.url")).isEqualTo("jdbc:postgresql://localhost:5432/verso?logServerErrorDetail=false");
            assertThat(env.getProperty("spring.flyway.user")).isEqualTo("svc_document_migrate");
            assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration/document");
            assertThat(env.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
            assertThat(env.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
            // The schema is infrastructure: Flyway never creates it and never works outside it (test review R12).
            assertThat(env.getProperty("spring.flyway.create-schemas")).isEqualTo("false");
            assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("document");
            assertThat(env.getProperty("spring.flyway.default-schema")).isEqualTo("document");
        }
    }

    /**
     * Review C2: Boot's config tree skips every file whose path contains a segment starting with "." (".." included),
     * so "optional:configtree:../secrets/" loaded nothing and the password placeholder reached the database as text.
     */
    @Test
    void configTreeImports_whenDeclared_haveNoDotSegments() throws java.io.IOException {
        java.nio.file.Path resources = java.nio.file.Path.of("src/main/resources");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(resources)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    int at = line.indexOf("configtree:");
                    if (at < 0) continue;
                    String path = line.substring(at + "configtree:".length()).replace("\"", "").trim();
                    assertThat(path.replace(java.io.File.separatorChar, '/').split("/")).as(file + ": " + line.trim())
                            .noneMatch(segment -> segment.startsWith("."));
                }
            }
        }
    }

    @Test
    void database_whenDeployProfile_comesFromEnvironmentPlaceholders() {
        try (ConfigurableApplicationContext context = start("prod")) {
            Environment env = context.getEnvironment();
            assertThatThrownBy(() -> env.getProperty("spring.datasource.url"))
                    .hasMessageContaining("DB_HOST");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("svc_document");
            assertThat(env.getProperty("spring.flyway.user")).isEqualTo("svc_document_migrate");
        }
    }

    private static ConfigurableApplicationContext start(String profile) {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles(profile);
        return app.run();
    }
}
