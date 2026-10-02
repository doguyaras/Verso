package com.verso.platform.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void doFilter_whenNoSpan_generatesW3cShapedIdForHeaderAndAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenDownstream.set(TraceIds.current((HttpServletRequest) req));
            }
        });

        String header = response.getHeader(TraceIds.HEADER);
        assertThat(header).matches("[0-9a-f]{32}");
        assertThat(response.getHeaders(TraceIds.HEADER)).hasSize(1);
        assertThat(request.getAttribute(TraceIds.REQUEST_ATTRIBUTE)).isEqualTo(header);
        assertThat(seenDownstream.get()).as("handlers read the same id").isEqualTo(header);
    }

    /** A downstream filter may commit the response (e.g. a 401 writer); the header must already be there. */
    @Test
    void doFilter_whenDownstreamCommitsResponse_headerIsAlreadySet() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> headerWhenCommitted = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest("GET", "/v1/x"), response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                headerWhenCommitted.set(((MockHttpServletResponse) res).getHeader(TraceIds.HEADER));
                res.flushBuffer();
            }
        });

        assertThat(headerWhenCommitted.get()).as("header before downstream commits").matches("[0-9a-f]{32}");
    }

    @Test
    void doFilter_whenSpanIsActive_reusesItsTraceId() throws Exception {
        String spanTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        MDC.put("traceId", spanTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/v1/anything"), response, new MockFilterChain());

        assertThat(response.getHeader(TraceIds.HEADER)).isEqualTo(spanTraceId);
    }

    @Test
    void doFilter_whenClientSendsTraceIdHeader_ignoresIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/anything");
        request.addHeader(TraceIds.HEADER, "attacker-chosen-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TraceIds.HEADER)).isNotEqualTo("attacker-chosen-id").matches("[0-9a-f]{32}");
    }

    /**
     * Only a W3C trace id (32 lower-case hex) is reused. Third-round test review J12: with a loosened pattern a short,
     * dashed or upper-case value passed, and only the CRLF case was pinned.
     */
    @Test
    void doFilter_whenMdcValueIsMalformed_generatesNewId() throws Exception {
        for (String malformed : new String[]{"not-a-trace-id\r\ninjected", "abc", "4bf92f35-77b3-4da6-a3ce-929d0e0e4736",
                "4BF92F3577B34DA6A3CE929D0E0E4736", "4bf92f3577b34da6a3ce929d0e0e47361"}) {
            MDC.put("traceId", malformed);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(new MockHttpServletRequest("GET", "/"), response, new MockFilterChain());

            assertThat(response.getHeader(TraceIds.HEADER)).as(malformed).matches("[0-9a-f]{32}").isNotEqualTo(malformed);
        }
    }

    @Test
    void current_whenFilterDidNotRun_stillReturnsAnId() {
        assertThat(TraceIds.current(new MockHttpServletRequest())).matches("[0-9a-f]{32}");
        assertThat(TraceIds.current(null)).matches("[0-9a-f]{32}");
    }
}
