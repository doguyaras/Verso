package com.verso.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The real database for application-context tests (reference 16: gerçek DB, gerçek Flyway migration'ları), applied
 * with {@link WithVersoPostgres}. One container per JVM; Spring's context cache shares it.
 *
 * <p>An initializer, not {@code @ImportTestcontainers} with {@code @DynamicPropertySource}: those properties arrive as
 * a bean, after the auto-configuration conditions ran, and Flyway's condition failed on the unresolved DB_HOST.
 *
 * <p>Same image as compose and the same deploy/postgres/initdb scripts, so the roles, grants and server settings
 * under test are the deployed ones. Passwords are random per run and handed to the init scripts as /run/secrets
 * files, exactly as compose does.
 *
 * <p>Deliberately not {@code @ServiceConnection}: it would connect the application as the container's superuser and
 * hide every missing GRANT. The application connects as svc_document and Flyway as svc_document_migrate.
 */
public final class VersoPostgres {

    /**
     * Pinned by digest like compose.yaml, so tests can never run against a re-pointed tag (phase 2 security review S6);
     * ImageVersionsTest compares the full reference with compose.
     */
    public static final String IMAGE =
            "pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0";
    public static final String DATABASE = "verso";

    static final Map<String, String> SECRETS = new LinkedHashMap<>();

    static {
        for (String name : new String[]{"SECRET_DB_DOCUMENT_MIGRATE_PASSWORD", "SECRET_DB_DOCUMENT_PASSWORD",
                "SECRET_DB_BACKUP_PASSWORD"}) {
            SECRETS.put(name, randomPassword());
        }
    }

    public static final PostgreSQLContainer POSTGRES = create();

    private VersoPostgres() {}

    /** Starts the container once and points the application and Flyway at it, with their own roles. */
    public static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            POSTGRES.start();
            TestPropertyValues.of(
                    "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                    "spring.datasource.username=svc_document",
                    "spring.datasource.password=" + secret("SECRET_DB_DOCUMENT_PASSWORD"),
                    "spring.flyway.url=" + POSTGRES.getJdbcUrl(),
                    "spring.flyway.user=svc_document_migrate",
                    "spring.flyway.password=" + secret("SECRET_DB_DOCUMENT_MIGRATE_PASSWORD"),
                    // Several cached test contexts share one container: small pools keep them far below
                    // max_connections (phase 2 spring review C4).
                    "spring.datasource.hikari.maximum-pool-size=3",
                    "spring.datasource.hikari.minimum-idle=0")
                    .applyTo(context);
        }
    }

    /** Names of the secret files the container gets (ModuleConsistencyTest). */
    public static java.util.Set<String> secretNames() {
        return java.util.Collections.unmodifiableSet(SECRETS.keySet());
    }

    public static String secret(String name) {
        String value = SECRETS.get(name);
        if (value == null) throw new IllegalArgumentException("unknown secret " + name);
        return value;
    }

    private static PostgreSQLContainer create() {
        PostgreSQLContainer container = new PostgreSQLContainer(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(DATABASE)
                .withUsername("postgres")
                .withPassword(randomPassword());
        Path initdb = repoRoot().resolve("deploy/postgres/initdb");
        try (Stream<Path> scripts = Files.list(initdb)) {
            scripts.filter(p -> p.toString().endsWith(".sh")).sorted().forEach(script -> container.withCopyFileToContainer(
                    MountableFile.forHostPath(script, 0755), "/docker-entrypoint-initdb.d/" + script.getFileName()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot list " + initdb, e);
        }
        SECRETS.forEach((name, value) -> container.withCopyToContainer(Transferable.of(value, 0644), "/run/secrets/" + name));
        return container;
    }

    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("deploy/postgres/initdb"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
        return dir;
    }

    private static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }
}
