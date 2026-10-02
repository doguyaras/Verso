package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.platform.core.handler.EnvelopeErrorController;
import com.verso.platform.core.handler.GlobalServiceExceptionHandler;
import com.verso.platform.observability.tracing.TraceIds;
import com.verso.support.WithVersoPostgres;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boots the real application on random ports (evidence level 1, no database yet) and checks the wiring unit tests
 * cannot see: starters load through AutoConfiguration.imports only, every response carries one X-Trace-Id, errors
 * raised anywhere use the envelope, raw request paths never reach the logs, and actuator lives on the management
 * port only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(VersoAppSmokeTest.PingController.class)
@WithVersoPostgres
class VersoAppSmokeTest {

    static final String PATH_MARKER = "path-marker-jane.doe@example.com";

    /** A 2xx endpoint that only exists in this test: the trace header must not depend on the error path. */
    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class PingController {
        @GetMapping("/v1/test-ping")
        String ping() {
            return "pong";
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    int serverPort;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    ApplicationContext context;

    Logger root;
    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(appender);
        appender.stop();
    }

    @Test
    void platformStarters_whenAppStarts_areLoadedByAutoConfigurationNotComponentScan() {
        // A component-scanned handler would also be a single bean (the auto-configuration backs off through
        // @ConditionalOnMissingBean), so the count alone proves nothing: the definition must come from the starter.
        assertDefinedByStarter(GlobalServiceExceptionHandler.class);
        assertDefinedByStarter(EnvelopeErrorController.class);
        assertThat(context.getBeansOfType(ErrorController.class)).hasSize(1);
    }

    /** Second-round review: without a provider, @Valid would be silently ignored in production. */
    @Test
    void beanValidation_whenAppStarts_hasARealProvider() {
        jakarta.validation.Validator validator = context.getBean(jakarta.validation.Validator.class);
        record Probe(@jakarta.validation.constraints.NotBlank String value) {}
        assertThat(validator.validate(new Probe(" "))).hasSize(1);
    }

    @Test
    void successResponse_whenServed_carriesSingleTraceIdFromFilter() throws Exception {
        HttpResponse<String> response = get(serverPort, "/v1/test-ping");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues(TraceIds.HEADER)).hasSize(1);
        assertThat(response.headers().firstValue(TraceIds.HEADER).orElseThrow()).matches("[0-9a-f]{32}");
    }

    @Test
    void unknownApiPath_whenRequested_returnsEnvelopedNotFound() throws Exception {
        HttpResponse<String> response = get(serverPort, "/v1/does-not-exist");

        assertEnvelope(response, 404, 90010);
    }

    /** Security review B6: /error used to answer 500 {"status":999} without the envelope. */
    @Test
    void errorPath_whenCalledDirectly_returnsEnvelopedNotFound() throws Exception {
        assertEnvelope(get(serverPort, "/error"), 404, 90010);
    }

    /** Security review B2: dot segments used to make the resource handler log the raw path at WARN. */
    @Test
    void dotSegmentPath_whenRequested_isNotLogged() throws Exception {
        HttpResponse<String> response = get(serverPort, "/v1/" + PATH_MARKER + "/../x");

        assertThat(response.statusCode()).isEqualTo(404);
        assertNoLogEventContains(PATH_MARKER);
    }

    /** Security review B2: Tomcat used to log rejected request lines with the raw path at INFO. */
    @Test
    void invalidRequestTarget_whenSent_isRejectedWithoutLoggingPath() throws Exception {
        String response = rawRequest("GET /v1/<" + PATH_MARKER + "> HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 400");
        assertNoLogEventContains(PATH_MARKER);
    }

    @Test
    void health_whenRequested_isServedOnManagementPortOnly() throws Exception {
        HttpResponse<String> management = get(managementPort, "/actuator/health");
        assertThat(management.statusCode()).isEqualTo(200);
        assertThat(management.body()).contains("\"status\":\"UP\"");

        HttpResponse<String> publicPort = get(serverPort, "/actuator/health");
        assertThat(publicPort.statusCode()).as("actuator must not be reachable on the API port").isEqualTo(404);
    }

    @Test
    void probes_whenRequested_areUp() throws Exception {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    // ---------- helpers ----------

    private void assertDefinedByStarter(Class<?> type) {
        String[] names = context.getBeanNamesForType(type);
        assertThat(names).as(type.getSimpleName()).hasSize(1);
        BeanDefinition definition = ((ConfigurableApplicationContext) context).getBeanFactory()
                .getBeanDefinition(names[0]);
        assertThat(definition.getFactoryBeanName()).as("factory of " + type.getSimpleName())
                .contains("PlatformCoreAutoConfiguration");
    }

    private static void assertEnvelope(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().allValues(TraceIds.HEADER)).hasSize(1);
        String traceId = response.headers().firstValue(TraceIds.HEADER).orElseThrow();
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.body())
                .contains("\"ok\":false")
                .contains("\"code\":" + code)
                .contains("\"traceId\":\"" + traceId + "\"");
    }

    private void assertNoLogEventContains(String marker) {
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage()).as("log message").doesNotContain(marker);
            if (e.getArgumentArray() != null) {
                assertThat(Stream.of(e.getArgumentArray()).map(String::valueOf).toList())
                        .noneMatch(a -> a.contains(marker));
            }
            for (var p = e.getThrowableProxy(); p != null; p = p.getCause()) {
                assertThat(String.valueOf(p.getMessage())).as("throwable message").doesNotContain(marker);
            }
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** java.net.http refuses to send an invalid request target, so this one is written on a plain socket. */
    private String rawRequest(String request) throws Exception {
        try (Socket socket = new Socket("localhost", serverPort)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
