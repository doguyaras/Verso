package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.observability.tracing.TraceIds;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Errors that never reach a controller: a servlet filter throws, the container cannot parse parameters, a multipart
 * body or a cookie. Second-round review (security B11/B12, spring-code, test-writer, 2026-10-02): Tomcat logged the
 * exception message with its stack trace at ERROR, client mistakes became 500, and a ServiceException thrown by a
 * filter lost its code. These run against the real Tomcat on a random port; phase 3's security filters will take
 * exactly this path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(ContainerErrorPathTest.Probes.class)
@VersoTestEnvironment
class ContainerErrorPathTest {

    static final String MARKER = "container-marker-Ahmet_maas_bordrosu.pdf";
    /**
     * Exception messages use plain text with spaces: MARKER is one 36-character run, which the sanitizer's opaque-block
     * rule redacts, so a sanitized message in the log went unnoticed (third-round review N3).
     */
    static final String PLAIN_MARKER = "Ahmet maas bordrosu 2026.pdf";
    static final String ERROR_DISPATCH_PROBE = "X-Probe-Error-Dispatch";

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {

        /** Stands in for a future security or rate-limit filter. */
        @Bean
        FilterRegistrationBean<Filter> throwingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                String uri = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
                if (uri.endsWith("/boom")) throw new IllegalStateException("boom " + PLAIN_MARKER);
                if (uri.endsWith("/late")) {
                    response.getWriter().write("partial");
                    response.flushBuffer();
                    throw new IllegalStateException("late " + PLAIN_MARKER);
                }
                if (uri.endsWith("/denied")) {
                    throw new ServiceException(CommonErrorCode.UNAUTHENTICATED, "NO_TOKEN");
                }
                // A filter that reads parameters (e.g. a rate limiter): the container fails before Spring MVC.
                if (uri.endsWith("/reads-param")) request.getParameter("q");
                if (uri.endsWith("/wrapped")) {
                    throw new jakarta.servlet.ServletException(new ServiceException(CommonErrorCode.UNAUTHENTICATED,
                            "NO_TOKEN", null, java.util.List.of("scheme=Bearer")));
                }
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/v1/test-filter/*");
            return registration;
        }

        /**
         * Registered like Spring Security's filter chain: every path, REQUEST and ERROR dispatch. When it fails again
         * during the error dispatch, Tomcat's host logger used to print the message and stack trace (third-round
         * review B20). Only requests carrying the probe header are affected, so the other tests are unchanged.
         */
        @Bean
        FilterRegistrationBean<Filter> errorDispatchFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                if (http.getHeader(ERROR_DISPATCH_PROBE) != null) {
                    throw new IllegalStateException(http.getDispatcherType() + " " + MARKER);
                }
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/*");
            registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
            return registration;
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/v1/test-params")
        String params(@RequestParam(value = "q", required = false) String q) {
            return "ok";
        }

        @org.springframework.web.bind.annotation.PutMapping("/v1/test-params")
        String put(@RequestParam(value = "q", required = false) String q) {
            return q == null ? "none" : "parsed";
        }

        @PostMapping("/v1/test-body")
        String body(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
            return "ok";
        }

        @PostMapping("/v1/test-upload")
        String upload(@RequestPart("file") MultipartFile file) {
            return "ok";
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    int serverPort;

    @Value("${local.management.port}")
    int managementPort;

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
    void filterException_whenUnexpected_returnsEnveloped500AndLogsTypesOnly() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/test-filter/boom")).GET());

        assertEnvelope(response, 500, CommonErrorCode.INTERNAL_ERROR.getCode());
        assertThat(response.body()).contains("\"path\":\"/v1/test-filter/boom\"");
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("exceptionType=IllegalStateException"));
        assertLogsFreeOf("bordrosu");
        assertLogsFreeOf("boom");
        assertNoThrowableLogged();
    }

    /** Third-round review N2: the error controller's own "unparsable request" branch, reached from a filter. */
    @Test
    void filterReadingParameter_whenEncodingIsInvalid_returns400FromErrorController() throws Exception {
        String response = raw("GET /v1/test-filter/reads-param?q=%ZZ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).contains("\"code\":" + CommonErrorCode.REQUEST_NOT_READABLE.getCode());
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getLoggerName().endsWith("EnvelopeErrorController")
                && e.getFormattedMessage().contains("code=REQUEST_NOT_READABLE"));
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    /** Third-round review N2: a filter may wrap the exception; code and safe details must survive the unwrap. */
    @Test
    void filterException_whenServiceExceptionWrappedInServletException_keepsCodeAndDetails() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/test-filter/wrapped")).GET());

        assertEnvelope(response, 401, CommonErrorCode.UNAUTHENTICATED.getCode());
        assertThat(response.body()).contains("\"details\":[\"scheme=Bearer\"]");
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    /** Third-round review N7: env, heapdump, loggers and configprops must never be exposed (reference 20). */
    @Test
    void actuator_whenSensitiveEndpointsRequested_areNotExposed() throws Exception {
        for (String path : new String[]{"/actuator/env", "/actuator/heapdump", "/actuator/loggers",
                "/actuator/configprops", "/actuator/threaddump", "/actuator/beans"}) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + managementPort + path)).GET());
            assertThat(response.statusCode()).as(path).isEqualTo(404);
        }
        HttpResponse<String> health = send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/health")).GET());
        assertThat(health.statusCode()).isEqualTo(200);
    }

    /**
     * Third-round review B25: the response was already committed (200), so the container's error status was 200 and
     * the controller answered 404 at WARN, hiding a server failure from alerting, and appended an envelope to the
     * body that had already been sent.
     */
    @Test
    void filterException_whenResponseAlreadyCommitted_logsServerErrorAndWritesNothingMore() throws Exception {
        // Raw socket: after a failure on a committed response Tomcat may end the connection mid-chunk.
        String response = raw("GET /v1/test-filter/late HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 200").contains("partial").doesNotContain("\"ok\":false");
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("code=INTERNAL_ERROR status=500")
                && e.getFormattedMessage().contains("exceptionType=IllegalStateException"));
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("status=404"));
        assertLogsFreeOf("bordrosu");
        assertNoThrowableLogged();
    }

    @Test
    void filterException_whenServiceException_keepsItsStatusAndCode() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/test-filter/denied")).GET());

        assertEnvelope(response, 401, CommonErrorCode.UNAUTHENTICATED.getCode());
        assertThat(response.body()).contains("\"service\":\"security\"");
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("code=UNAUTHENTICATED") && e.getFormattedMessage().contains("reason=NO_TOKEN"));
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    @Test
    void filterException_whenErrorDispatchFailsToo_isNotLoggedAndNotEchoed() throws Exception {
        String response = raw("GET /v1/test-params HTTP/1.1\r\nHost: localhost\r\n" + ERROR_DISPATCH_PROBE
                + ": 1\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 500");
        assertThat(response).doesNotContain(MARKER).doesNotContain("IllegalStateException");
        assertLogsFreeOf(MARKER);
        assertNoThrowableLogged();
    }

    @Test
    void traceMethod_whenRejectedByContainer_returnsEnvelopeAndLogsNoPath() throws Exception {
        String response = raw("TRACE /v1/" + MARKER + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 405");
        assertThat(response).contains("\"ok\":false").contains("\"code\":");
        assertLogsFreeOf(MARKER);
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    @Test
    void queryParameter_whenEncodingIsInvalid_returns400WithoutStackTrace() throws Exception {
        String response = raw("GET /v1/test-params?q=%ZZ" + MARKER + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).contains("\"code\":" + CommonErrorCode.REQUEST_NOT_READABLE.getCode());
        assertRejectionLogged("REQUEST_NOT_READABLE");
        assertLogsFreeOf(MARKER);
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    /**
     * JSON API decision (ADR-0007 #18): form bodies on PUT/PATCH/DELETE are never parsed into parameters. The body
     * also carries a broken escape: the form-content filter used to decode it and fail with the decoder message in
     * the log (second-round review B11). With the filter back on, the answer would be "parsed" or an error.
     */
    @Test
    void formBody_whenPut_isNotParsedIntoParameters() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/test-params"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method("PUT", HttpRequest.BodyPublishers.ofString("q=value&bad=%ZZ" + MARKER)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("none");
        assertLogsFreeOf(MARKER);
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    @Test
    void multipartBody_whenMalformed_returns400WithoutStackTrace() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/test-upload"))
                .header("Content-Type", "multipart/form-data; boundary=xyz")
                .POST(HttpRequest.BodyPublishers.ofString("--xyz\r\nContent-Disposition: form-data; name=\"file\"; "
                        + "filename=\"" + MARKER + "\"\r\n\r\nno closing boundary " + MARKER)));

        assertEnvelope(response, 400, CommonErrorCode.REQUEST_NOT_READABLE.getCode());
        assertRejectionLogged("REQUEST_NOT_READABLE");
        assertLogsFreeOf(MARKER);
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    /**
     * A broken chunked body fails inside Tomcat's input filter: Spring MVC answers 400, then the container marks the
     * request bad and dispatches to /error with its own BadRequestException, which used to end as code VALIDATION
     * (third-round security review, out-of-scope note).
     */
    @Test
    void chunkedBody_whenMalformed_returnsRequestNotReadable() throws Exception {
        String response = raw("POST /v1/test-body HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\nZZ" + MARKER + "\r\n{}\r\n0\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).contains("\"code\":" + CommonErrorCode.REQUEST_NOT_READABLE.getCode());
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("code=VALIDATION"));
        assertLogsFreeOf(MARKER);
        assertNoErrorLogged();
        assertNoThrowableLogged();
    }

    @Test
    void cookieHeader_whenMalformed_isNotLogged() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/test-params"))
                .header("Cookie", "session=a\"" + MARKER + ";;; other").GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertLogsFreeOf(MARKER);
    }

    // ---------- helpers ----------

    private URI uri(String path) {
        return URI.create("http://localhost:" + serverPort + path);
    }

    /** Every request carries a valid access token (ADR-0005): these tests are about what happens after it. */
    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.setHeader("Authorization", TestIdp.bearer("acct-container"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** java.net.http rejects invalid percent-encoding, so this request is written on a plain socket. */
    private String raw(String request) throws Exception {
        try (Socket socket = new Socket("localhost", serverPort)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(TestIdp.withToken(request).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buffer);
            return buffer.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static void assertEnvelope(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().allValues(TraceIds.HEADER)).hasSize(1);
        String traceId = response.headers().firstValue(TraceIds.HEADER).orElseThrow();
        assertThat(response.body()).contains("\"ok\":false").contains("\"code\":" + code)
                .contains("\"traceId\":\"" + traceId + "\"");
    }

    private void assertLogsFreeOf(String marker) {
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage()).as("message of " + e.getLoggerName()).doesNotContain(marker);
            if (e.getArgumentArray() != null) {
                assertThat(Stream.of(e.getArgumentArray()).map(String::valueOf).toList())
                        .noneMatch(a -> a.contains(marker));
            }
            for (var p = e.getThrowableProxy(); p != null; p = p.getCause()) {
                assertThat(String.valueOf(p.getMessage())).as("throwable of " + e.getLoggerName()).doesNotContain(marker);
            }
        }
    }

    /** Silence is not enough: the rejection itself must be visible with its code (third-round review N8). */
    private void assertRejectionLogged(String code) {
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("code=" + code + " status=400"));
    }

    /** A rejected client request is not an application failure: no ERROR line, no alarm noise. */
    private void assertNoErrorLogged() {
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.ERROR)
                .as("ERROR events").isEmpty();
    }

    private void assertNoThrowableLogged() {
        assertThat(appender.list).filteredOn(e -> e.getThrowableProxy() != null)
                .as("log events carrying a throwable (stack trace)").isEmpty();
    }
}
