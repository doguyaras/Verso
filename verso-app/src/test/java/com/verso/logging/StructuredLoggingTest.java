package com.verso.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structured logging evidence (reference 8.1): with logging.structured.format.console=ecs console lines are JSON
 * with ECS fields. In deployments the property comes from config/verso.yml (every profile except local).
 *
 * <p>TRAP (reference Ek B): Logback's LoggingSystem is initialised once per JVM; if an earlier Spring test in the same
 * surefire JVM started it with plain text, our property is silently ignored. cleanUp() forces re-initialisation
 * before the context loads and again afterwards so later tests set up their own format.
 */
@SpringBootTest(classes = StructuredLoggingTest.Empty.class, properties = "logging.structured.format.console=ecs")
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    @Configuration(proxyBeanMethods = false)
    static class Empty {}

    @BeforeAll
    static void forceLoggingReinitialization() {
        LoggingSystem.get(StructuredLoggingTest.class.getClassLoader()).cleanUp();
    }

    @AfterAll
    static void releaseLoggingSystem() {
        LoggingSystem.get(StructuredLoggingTest.class.getClassLoader()).cleanUp();
    }

    @Test
    void consoleLine_whenEcsFormat_isEcsJson(CapturedOutput output) {
        String marker = "ecs-probe-" + UUID.randomUUID();
        LoggerFactory.getLogger("com.verso.EcsProbe").info("Structured probe: outcome=SUCCESS marker={}", marker);

        String line = output.getOut().lines().filter(l -> l.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("probe line missing from console; output:\n" + output.getOut()));
        assertThat(line).startsWith("{").endsWith("}");

        JsonNode json = JsonMapper.builder().build().readTree(line);
        assertThat(json.get("@timestamp").asString()).as("@timestamp").isNotBlank();
        assertThat(json.at("/log/level").asString()).as("log.level").isEqualTo("INFO");
        assertThat(json.at("/log/logger").asString()).isEqualTo("com.verso.EcsProbe");
        assertThat(json.get("message").asString()).contains("outcome=SUCCESS").contains(marker);
        assertThat(json.at("/ecs/version").asString()).as("ecs.version").isNotBlank();
        assertThat(json.at("/process/pid").isNumber()).isTrue();
    }
}
