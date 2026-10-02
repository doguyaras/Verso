package com.verso.platform.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.observability.tracing.TraceIdFilter;
import com.verso.platform.observability.tracing.TraceIds;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global handler mapping (reference 7.3) and privacy (reference 8.4, 8.5, llm-rules 2.1): every failure produces the
 * single envelope plus exactly one X-Trace-Id; neither the response nor any log event (message, arguments, MDC,
 * throwable) contains rejected values, exception text, file names or secrets. Evidence level 1 (standalone MockMvc).
 * The ListAppender sits on the root logger so framework log lines are inspected as well.
 */
class GlobalServiceExceptionHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    static final String PHONE_MARKER = "+905551234567";
    static final String TOKEN_MARKER = "SECRET-TOKEN-MARKER-9f8e7d6c";
    static final String VALUE_MARKER = "rejected-value-marker-31337";
    static final String FILE_MARKER = "Ahmet_maas_bordrosu.pdf";

    enum ProbeErrorCode implements ErrorCode {
        PROBE_CONFLICT(42001, "Probe is in a conflicting state.", HttpStatus.CONFLICT),
        PROBE_UNAVAILABLE(42002, "Probe dependency is unavailable.", HttpStatus.SERVICE_UNAVAILABLE);

        private final int code;
        private final String message;
        private final HttpStatus status;

        ProbeErrorCode(int code, String message, HttpStatus status) {
            this.code = code;
            this.message = message;
            this.status = status;
        }

        @Override public int getCode() { return code; }
        @Override public String getMessage() { return message; }
        @Override public String getService() { return "probe"; }
        @Override public HttpStatus getHttpStatus() { return status; }
    }

    @RestController
    @RequestMapping("/v1/probe")
    static class ProbeController {

        record ProbeBody(@Pattern(regexp = "[A-Z0-9-]{3,20}") String code) {}

        /** Cross-field rule (global error) and a message that would interpolate the rejected value. */
        record RangeBody(Integer from, Integer to,
                         @Size(max = 3, message = "value ${validatedValue} is too long") String label) {
            @AssertTrue(message = "from must not exceed to")
            boolean isOrdered() {
                return from == null || to == null || from <= to;
            }
        }

        record Filter(UUID id, Integer page) {}

        /** Class-level rule whose message renders a property through EL, plus a map keyed by client input. */
        @ClassCheck(message = "bean ${validatedValue.phone} mismatch")
        record GlobalBody(String phone,
                          java.util.Map<String, @jakarta.validation.constraints.NotBlank String> attrs,
                          @Size(min = 2, message = "{jakarta.validation.constraints.Size.message}") String name,
                          @Size(max = 2, message = "starts ${formatter.format('%.6s', validatedValue)}") String fmt,
                          List<@Valid Item> items) {}

        record Item(@jakarta.validation.constraints.NotBlank String name) {}

        @GetMapping("/interpolated")
        ResponseEntity<String> interpolated(
                @RequestParam("q") @Size(max = 3, message = "too long: ${validatedValue}") String q) {
            return ResponseEntity.ok(q);
        }

        @PostMapping("/global")
        ResponseEntity<Void> global(@Valid @RequestBody GlobalBody body) {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/constraint-two")
        ResponseEntity<Void> constraintTwo() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            throw new ConstraintViolationException(validator.validate(new TwoRules(null, null)));
        }

        record TwoRules(@jakarta.validation.constraints.NotNull String zeta,
                        @jakarta.validation.constraints.NotNull String alpha) {}

        /** Method-parameter violations carry the method name in their path (create.email). */
        @PostMapping("/constraint-method")
        ResponseEntity<Void> constraintMethod() throws NoSuchMethodException {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            java.lang.reflect.Method create = MethodTarget.class.getDeclaredMethod("create", String.class);
            throw new ConstraintViolationException(validator.forExecutables()
                    .validateParameters(new MethodTarget(), create, new Object[]{" "}));
        }

        static class MethodTarget {
            void create(@jakarta.validation.constraints.NotBlank String email) {}
        }

        @PostMapping
        ResponseEntity<Void> post(@Valid @RequestBody ProbeBody body) {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/range")
        ResponseEntity<Void> range(@Valid @RequestBody RangeBody body) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/search")
        ResponseEntity<String> search(@Valid @ModelAttribute Filter filter) {
            return ResponseEntity.ok("ok");
        }

        @GetMapping("/query")
        ResponseEntity<String> query(@RequestParam("q") @Size(max = 3) String q) {
            return ResponseEntity.ok(q);
        }

        @GetMapping(value = "/formats/json", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<String> json() {
            return ResponseEntity.ok("{}");
        }

        @GetMapping("/{probeId}")
        ResponseEntity<String> get(@PathVariable("probeId") UUID probeId,
                                   @RequestHeader("X-Probe-Key") UUID key) {
            return ResponseEntity.ok(probeId.toString());
        }

        @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        ResponseEntity<Void> upload(@RequestPart("file") MultipartFile file) {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/too-large")
        ResponseEntity<Void> tooLarge() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @PostMapping("/conflict")
        ResponseEntity<Void> conflict() {
            throw new ServiceException(ProbeErrorCode.PROBE_CONFLICT, "STATE_LOCKED", "BUSINESS_RULE",
                    List.of("retryAfterSeconds=120"));
        }

        @PostMapping("/unavailable")
        ResponseEntity<Void> unavailable() {
            throw new ServiceException(ProbeErrorCode.PROBE_UNAVAILABLE, "MODEL_TIMEOUT",
                    new IllegalStateException("connect to http://10.0.0.5:11434 failed token=" + TOKEN_MARKER));
        }

        @PostMapping("/boom")
        ResponseEntity<Void> boom() {
            throw new IllegalStateException("Key (owner_id, title)=(42, " + FILE_MARKER + ") token=" + TOKEN_MARKER
                    + " phone=" + PHONE_MARKER);
        }

        @PostMapping("/status/{code}")
        ResponseEntity<Void> status(@PathVariable("code") int code) {
            throw new ResponseStatusException(HttpStatus.valueOf(code), "reason with " + VALUE_MARKER);
        }

        @PostMapping("/async-timeout")
        ResponseEntity<Void> asyncTimeout() throws Exception {
            throw new AsyncRequestTimeoutException();
        }

        @PostMapping("/constraint")
        ResponseEntity<Void> constraint() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            throw new ConstraintViolationException(validator.validate(new RangeBody(1, 2, VALUE_MARKER)));
        }
    }

    /** Test-only class-level constraint that always fails, to exercise global errors. */
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @jakarta.validation.Constraint(validatedBy = ClassCheckValidator.class)
    @interface ClassCheck {
        String message();
        Class<?>[] groups() default {};
        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }

    public static class ClassCheckValidator implements jakarta.validation.ConstraintValidator<ClassCheck, Object> {
        @Override
        public boolean isValid(Object value, jakarta.validation.ConstraintValidatorContext context) {
            return false;
        }
    }

    MockMvc mvc;
    MockMvc mvcWithTraceFilter;
    Logger root;
    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        GlobalServiceExceptionHandler handler = new GlobalServiceExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController()).setControllerAdvice(handler).build();
        mvcWithTraceFilter = MockMvcBuilders.standaloneSetup(new ProbeController()).setControllerAdvice(handler)
                .addFilters(new TraceIdFilter()).build();

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

    // ---------- validation and binding ----------

    @Test
    void requestBody_whenConstraintFails_returns400ValidationWithoutRejectedValue() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + VALUE_MARKER + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.VALIDATION.getCode()))
                .andExpect(jsonPath("$.error.service").value("validation"))
                .andExpect(jsonPath("$.error.details[0]").value(org.hamcrest.Matchers.startsWith("code=")))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
        assertThat(renderedLogs()).anyMatch(m -> m.contains("code=VALIDATION") && m.contains("status=400"));
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    @Test
    void requestBody_whenMessageInterpolatesValue_returnsGenericMessageInstead() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/range").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + VALUE_MARKER + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0]").value("label=invalid"))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    @Test
    void requestBody_whenCrossFieldRuleFails_returnsObjectNameDetail() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/range").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":5,\"to\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0]").value("ordered=from must not exceed to"))
                .andReturn();
        assertEnvelope(r);
    }

    @Test
    void requestBody_whenMalformedJson_returns400NotReadableWithoutPayloadText() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": " + VALUE_MARKER + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.REQUEST_NOT_READABLE.getCode()))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("Unrecognized");
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    /** Review finding (spring/test/security, 2026-10-02): binding failures used to echo Spring's raw message. */
    @Test
    void modelAttribute_whenTypeMismatch_returns400WithoutRejectedValueOrTypeNames() throws Exception {
        MvcResult r = mvc.perform(get("/v1/probe/search").param("id", FILE_MARKER).param("page", VALUE_MARKER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.VALIDATION.getCode()))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, FILE_MARKER, VALUE_MARKER, "java.", "Failed to convert");
        assertThat(r.getResponse().getContentAsString()).contains("id=invalid").contains("page=invalid");
        assertNoMarkerAnywhereInLogs(FILE_MARKER);
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    @Test
    void requestParam_whenConstraintFails_returns400NamingTheParameterWithoutValue() throws Exception {
        MvcResult r = mvc.perform(get("/v1/probe/query").param("q", VALUE_MARKER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.VALIDATION.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value(org.hamcrest.Matchers.startsWith("q=")))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    /** Second-round review (R4b): a method-validation message interpolating the value must not be shown. */
    @Test
    void requestParam_whenMessageInterpolatesValue_returnsGenericMessage() throws Exception {
        MvcResult r = mvc.perform(get("/v1/probe/interpolated").param("q", VALUE_MARKER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0]").value("q=invalid"))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
    }

    /**
     * Second-round review: a class-level rule rendered a property of the whole object, a formatter rendered a prefix
     * of the value (both bypassed the old "message contains value" check), and a map key from the client landed in
     * the field path. A short value must not hide a harmless rule message either (old false positive).
     */
    @Test
    void requestBody_whenMessagesUseExpressionsOrMapKeys_neverEchoClientData() throws Exception {
        String phone = "+905551112233";
        String body = "{\"phone\":\"" + phone + "\",\"attrs\":{\"" + FILE_MARKER + "\":\"\"},"
                + "\"name\":\"a\",\"fmt\":\"" + VALUE_MARKER + "\"}";
        MvcResult r = mvc.perform(post("/v1/probe/global").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, phone, FILE_MARKER, VALUE_MARKER, "rejected-va");
        assertThat(r.getResponse().getContentAsString())
                .contains("globalBody=invalid")
                .contains("fmt=invalid")
                .contains("attrs[]")
                .contains("name=size must be between 2 and");
        assertNoMarkerAnywhereInLogs(phone);
        assertNoMarkerAnywhereInLogs(FILE_MARKER);
    }

    /** Third-round review B21: a "]" inside the key ended the old regex match early and leaked the rest of the key. */
    @Test
    void requestBody_whenMapKeyContainsBrackets_keepsOnlyThePropertyPath() throws Exception {
        String body = "{\"attrs\":{\"x]jane.doe@probe.example\":\"\",\"[a]][b.\":\"\"},\"name\":\"ab\"}";
        MvcResult r = mvc.perform(post("/v1/probe/global").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, "jane.doe", "probe.example", "[a]", "[b");
        assertThat(r.getResponse().getContentAsString()).contains("\"attrs[]=must not be blank\"");
    }

    /** The index is dropped, the property after it is kept: the client still learns which field to fix. */
    @Test
    void requestBody_whenListElementPropertyFails_keepsPropertyAfterTheIndex() throws Exception {
        String body = "{\"name\":\"ab\",\"items\":[{\"name\":\"ok\"},{\"name\":\"\"}]}";
        MvcResult r = mvc.perform(post("/v1/probe/global").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEnvelope(r);
        assertThat(r.getResponse().getContentAsString()).contains("\"items[].name=must not be blank\"")
                .doesNotContain("items[1]");
    }

    @Test
    void constraintViolation_whenSeveral_returnsSortedLeafNamesWithoutMethodNames() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/constraint-two"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0]").value("alpha=must not be null"))
                .andExpect(jsonPath("$.error.details[1]").value("zeta=must not be null"))
                .andReturn();
        assertEnvelope(r);
    }

    /** Second-round review: the full property path exposed Java method names (create.email). */
    @Test
    void constraintViolation_whenFromMethodParameter_showsLeafNameOnly() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/constraint-method"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0]").value("email=must not be blank"))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, "create.", "arg0");
    }

    @Test
    void requestParam_whenMissing_returns400NamingTheParameterOnly() throws Exception {
        MvcResult r = mvc.perform(get("/v1/probe/query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.MISSING_PARAMETER.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value("parameter=q"))
                .andReturn();
        assertEnvelope(r);
    }

    @Test
    void constraintViolation_whenThrownByService_returns400WithSortedPathAndGenericValueMessage() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.VALIDATION.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value("label=invalid"))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    @Test
    void pathVariable_whenTypeMismatch_returns400NamingTheParameterOnly() throws Exception {
        MvcResult r = mvc.perform(get("/v1/probe/{probeId}", VALUE_MARKER).header("X-Probe-Key", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.TYPE_MISMATCH.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value("parameter=probeId"))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    @Test
    void requestHeader_whenMissing_returns400NamingTheHeaderOnly() throws Exception {
        MvcResult r = mvc.perform(get("/v1/probe/{probeId}", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.MISSING_PARAMETER.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value("header=X-Probe-Key"))
                .andReturn();
        assertEnvelope(r);
    }

    @Test
    void requestPart_whenMissing_returns400NamingThePartOnly() throws Exception {
        MvcResult r = mvc.perform(multipart("/v1/probe/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.MISSING_PARAMETER.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value("part=file"))
                .andReturn();
        assertEnvelope(r);
    }

    // ---------- routing and content negotiation ----------

    @Test
    void unknownPath_whenRequested_returns404EnvelopeNot500() throws Exception {
        MvcResult r = mvc.perform(get("/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.NOT_FOUND.getCode()))
                .andReturn();
        assertEnvelope(r);
    }

    /** RFC 9110 15.5.6: a 405 response must carry Allow. */
    @Test
    void httpMethod_whenNotSupported_returns405WithAllowHeader() throws Exception {
        MvcResult r = mvc.perform(delete("/v1/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.METHOD_NOT_ALLOWED.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value("allowed=POST"))
                .andReturn();
        assertEnvelope(r);
    }

    @Test
    void contentType_whenNotSupported_returns415WithAcceptHeader() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe").contentType(MediaType.TEXT_PLAIN).content("code=ABC"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().exists("Accept"))
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode()))
                .andExpect(jsonPath("$.error.details[0]").value(org.hamcrest.Matchers.startsWith("supported=")))
                .andReturn();
        assertEnvelope(r);
    }

    @Test
    void acceptHeader_whenNotSatisfiable_returns406JsonEnvelope() throws Exception {
        MvcResult r = mvc.perform(get("/v1/probe/formats/json").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.NOT_ACCEPTABLE.getCode()))
                .andReturn();
        assertEnvelope(r);
    }

    @Test
    void upload_whenTooLarge_returns413PayloadTooLarge() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/too-large"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.PAYLOAD_TOO_LARGE.getCode()))
                .andReturn();
        assertEnvelope(r);
    }

    /** Review finding: unmapped statuses used to be reported as "validation" whatever the status was. */
    @Test
    void responseStatusException_whenUnmappedStatus_usesCodeMatchingTheStatus() throws Exception {
        expectStatusCode(401, CommonErrorCode.UNAUTHENTICATED);
        expectStatusCode(403, CommonErrorCode.ACCESS_DENIED);
        expectStatusCode(404, CommonErrorCode.NOT_FOUND);
        expectStatusCode(409, CommonErrorCode.REQUEST_CONFLICT);
        expectStatusCode(418, CommonErrorCode.REQUEST_REJECTED);
        expectStatusCode(429, CommonErrorCode.TOO_MANY_REQUESTS);
        assertNoMarkerAnywhereInLogs(VALUE_MARKER);
    }

    // ---------- business and unexpected failures ----------

    @Test
    void serviceException_whenClientSide_returnsItsCodeAndLogsSafeReasonOnly() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(42001))
                .andExpect(jsonPath("$.error.service").value("probe"))
                .andExpect(jsonPath("$.error.message").value("Probe is in a conflicting state."))
                .andExpect(jsonPath("$.error.details[0]").value("retryAfterSeconds=120"))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, "STATE_LOCKED", "BUSINESS_RULE");
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("code=PROBE_CONFLICT")
                && e.getFormattedMessage().contains("reason=STATE_LOCKED")
                && e.getFormattedMessage().contains("category=BUSINESS_RULE")
                && e.getFormattedMessage().contains("status=409"));
    }

    @Test
    void serviceException_whenServerSideWithCause_logsErrorWithoutCauseText() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value(42002))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, "10.0.0.5", TOKEN_MARKER);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("code=PROBE_UNAVAILABLE")
                && e.getFormattedMessage().contains("reason=MODEL_TIMEOUT"));
        assertNoMarkerAnywhereInLogs(TOKEN_MARKER);
        assertNoMarkerAnywhereInLogs("10.0.0.5");
    }

    /**
     * Review finding: the 500 log used to carry a "sanitized" root-cause message, which still leaked file names and
     * hosts. Now only types are logged (llm-rules 2.1); the message never reaches the log at all.
     */
    @Test
    void unexpectedException_whenThrown_returns500AndLogsTypesOnly() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.INTERNAL_ERROR.getCode()))
                .andExpect(jsonPath("$.error.service").value("system"))
                .andExpect(jsonPath("$.error.message").value("Unexpected error."))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, "IllegalStateException", TOKEN_MARKER, FILE_MARKER, PHONE_MARKER);
        assertThat(renderedLogs()).anyMatch(m -> m.contains("code=INTERNAL_ERROR")
                && m.contains("exceptionType=IllegalStateException") && m.contains("rootCauseType=IllegalStateException"));
        assertNoMarkerAnywhereInLogs(TOKEN_MARKER);
        assertNoMarkerAnywhereInLogs(PHONE_MARKER);
        assertNoMarkerAnywhereInLogs(FILE_MARKER);
        assertNoMarkerAnywhereInLogs("owner_id");
        // The raw throwable is never attached: its stack trace would carry the message.
        assertThat(appender.list).filteredOn(e -> e.getFormattedMessage().contains("code=INTERNAL_ERROR"))
                .isNotEmpty()
                .allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    void frameworkServerError_whenRaised_returnsInternalErrorAndLogsTypesOnly() throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/async-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.INTERNAL_ERROR.getCode()))
                .andExpect(jsonPath("$.error.service").value("system"))
                .andReturn();
        assertEnvelope(r);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("exceptionType=AsyncRequestTimeoutException"));
        assertThat(appender.list).filteredOn(e -> e.getFormattedMessage().contains("code=INTERNAL_ERROR"))
                .allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    void errorResponse_whenTraceFilterRan_carriesItsTraceIdExactlyOnce() throws Exception {
        MvcResult r = mvcWithTraceFilter.perform(post("/v1/probe/conflict"))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(r.getResponse().getHeaders(TraceIds.HEADER)).hasSize(1);
        assertEnvelope(r);
        String traceId = r.getResponse().getHeader(TraceIds.HEADER);
        assertThat(renderedLogs()).anyMatch(m -> m.contains("traceId=" + traceId));
    }

    // ---------- helpers ----------

    void expectStatusCode(int status, CommonErrorCode expected) throws Exception {
        MvcResult r = mvc.perform(post("/v1/probe/status/{code}", status))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(expected.getCode()))
                .andExpect(jsonPath("$.error.service").value(expected.getService()))
                .andReturn();
        assertEnvelope(r);
        assertBodyFreeOf(r, VALUE_MARKER);
    }

    /** Every error response: one X-Trace-Id header, envelope fields, traceId = header, timestamp = Clock. */
    static void assertEnvelope(MvcResult r) throws Exception {
        assertThat(r.getResponse().getHeaders(TraceIds.HEADER)).as("X-Trace-Id header").hasSize(1);
        String traceId = r.getResponse().getHeader(TraceIds.HEADER);
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(r.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(r.getResponse().getContentAsString())
                .contains("\"ok\":false")
                .contains("\"traceId\":\"" + traceId + "\"")
                .contains("\"timestamp\":" + NOW.toEpochMilli())
                .contains("\"path\":\"" + r.getRequest().getRequestURI() + "\"");
    }

    /**
     * The envelope's "path" echoes the caller's own request URI (reference 6.2); the markers must not appear anywhere
     * else in the body (details, message).
     */
    static void assertBodyFreeOf(MvcResult r, String... markers) throws Exception {
        String body = r.getResponse().getContentAsString().replace(r.getRequest().getRequestURI(), "");
        for (String marker : markers) {
            assertThat(body).as("response body").doesNotContain(marker);
        }
    }

    List<String> renderedLogs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Reference 8.5: the marker is absent from the rendered message, the arguments, the MDC and the throwable chain. */
    void assertNoMarkerAnywhereInLogs(String marker) {
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage()).as("formatted message").doesNotContain(marker);
            if (e.getArgumentArray() != null) {
                assertThat(Stream.of(e.getArgumentArray()).map(String::valueOf).toList())
                        .noneMatch(a -> a.contains(marker));
            }
            assertThat(e.getMDCPropertyMap().values()).noneMatch(v -> v.contains(marker));
            for (var p = e.getThrowableProxy(); p != null; p = p.getCause()) {
                assertThat(String.valueOf(p.getMessage())).as("throwable message").doesNotContain(marker);
            }
        }
    }
}
