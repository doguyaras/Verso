package com.verso.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.testing.TestPdfs;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Review E2/S2: uploads held in memory at once are bounded; the next one gets 503 with Retry-After. */
class UploadLimiterTest {

    @Test
    void preHandle_whenAllPermitsAreTaken_rejectsWithRetryAfterAndFreesThemAfterCompletion() {
        UploadLimiter limiter = new UploadLimiter(TestPdfs.properties()); // 4 permits
        MockHttpServletRequest[] uploads = new MockHttpServletRequest[4];
        for (int i = 0; i < uploads.length; i++) {
            uploads[i] = new MockHttpServletRequest("POST", "/v1/documents");
            assertThat(limiter.preHandle(uploads[i], new MockHttpServletResponse(), null)).isTrue();
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertThatThrownBy(() -> limiter.preHandle(new MockHttpServletRequest("POST", "/v1/documents"), rejected, null))
                .isInstanceOfSatisfying(DocumentServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DocumentErrorCode.DOCUMENT_UPLOADS_BUSY));
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("5");

        limiter.afterCompletion(uploads[0], new MockHttpServletResponse(), null, null);
        limiter.afterCompletion(uploads[0], new MockHttpServletResponse(), null, null); // released once only
        assertThat(limiter.preHandle(new MockHttpServletRequest("POST", "/v1/documents"), new MockHttpServletResponse(), null))
                .isTrue();
        assertThatThrownBy(() -> limiter.preHandle(new MockHttpServletRequest("POST", "/v1/documents"),
                new MockHttpServletResponse(), null)).isInstanceOf(DocumentServiceException.class);
    }

    @Test
    void preHandle_whenNotAnUpload_takesNoPermit() {
        UploadLimiter limiter = new UploadLimiter(TestPdfs.properties());
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.preHandle(new MockHttpServletRequest("GET", "/v1/documents"), new MockHttpServletResponse(),
                    null)).isTrue();
        }
        assertThat(limiter.inProgress(TestPdfs.properties())).isZero();
    }
}
