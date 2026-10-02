package com.verso.platform.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.multipart.MultipartException;

class ErrorClassifierTest {

    @Test
    void isMalformedRequest_whenParsingFailureAnywhereInChain_returnsTrue() {
        assertThat(ErrorClassifier.isMalformedRequest(new MultipartException("x"))).isTrue();
        assertThat(ErrorClassifier.isMalformedRequest(
                new HttpMessageNotReadableException("x", new MockHttpInputMessage(new byte[0])))).isTrue();
        assertThat(ErrorClassifier.isMalformedRequest(
                new IllegalStateException("wrapper", new InvalidParameterException("bad %ZZ")))).isTrue();
    }

    @Test
    void isMalformedRequest_whenApplicationFailure_returnsFalse() {
        assertThat(ErrorClassifier.isMalformedRequest(new IllegalStateException("boom"))).isFalse();
        assertThat(ErrorClassifier.isMalformedRequest(null)).isFalse();
    }

    @Test
    void isMalformedRequest_whenCauseChainIsCyclic_terminates() {
        var a = new IllegalStateException("a");
        var b = new IllegalArgumentException("b", a);
        a.initCause(b);
        assertThat(ErrorClassifier.isMalformedRequest(a)).isFalse();
    }

    @Test
    void unwrapServlet_whenFilterExceptionWrapped_returnsTheCause() {
        var cause = new IllegalStateException("filter");
        assertThat(ErrorClassifier.unwrapServlet(new ServletException(new ServletException(cause)))).isSameAs(cause);
        assertThat(ErrorClassifier.unwrapServlet(cause)).isSameAs(cause);
    }

    /**
     * Container types are matched by name (platform-core has no compile dependency on Tomcat). A renamed class would
     * silently turn a client error into a 500 or a VALIDATION code: the names must exist (third-round review; the
     * first guess, org.apache.catalina.connector.BadRequestException, does not exist in Tomcat 11).
     */
    @Test
    void containerTypeNames_whenTomcatIsOnTheClasspath_resolveToRealExceptions() throws Exception {
        for (String name : new String[]{ErrorClassifier.TOMCAT_INVALID_PARAMETER, ErrorClassifier.TOMCAT_BAD_REQUEST}) {
            assertThat(Throwable.class.isAssignableFrom(Class.forName(name))).as(name).isTrue();
        }
        Throwable badRequest = (Throwable) Class.forName(ErrorClassifier.TOMCAT_BAD_REQUEST)
                .getConstructor(String.class).newInstance("broken chunk");
        assertThat(ErrorClassifier.isMalformedRequest(new ServletException(badRequest))).isTrue();
    }
}
