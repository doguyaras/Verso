package com.verso.privacy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.privacy.exception.PrivacyErrorCode;
import com.verso.privacy.exception.PrivacyServiceException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The sub of a request has a token subject's shape (JwtValidation.SUBJECT) or is refused before any query. */
class SubjectIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {"6f1c2d3e-0000-4000-8000-000000000001", "acct-audit-user-1", "user@example.org",
            "auth0:abc.def_ghi", "a"})
    void require_whenTheShapeOfATokenSubject_returnsIt(String sub) {
        assertThat(SubjectIds.require(sub)).isEqualTo(sub);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a b", "%", "a%", "a_%", "a/b", "a\\b", "a'b", "a\"b", "a;b", "a\nb", "a\u0000b",
            "ş", "a*", "a?b", "a+b", "a|b"})
    void require_whenNotATokenSubject_isRefusedWith13001(String sub) {
        assertThatThrownBy(() -> SubjectIds.require(sub)).isInstanceOf(PrivacyServiceException.class)
                .satisfies(e -> assertThat(((PrivacyServiceException) e).getErrorCode())
                        .isEqualTo(PrivacyErrorCode.SUBJECT_INVALID))
                .message().doesNotContain(sub.isBlank() ? "\u0001" : sub);
    }

    @Test
    void require_whenNullOrTooLong_isRefused() {
        assertThatThrownBy(() -> SubjectIds.require(null)).isInstanceOf(PrivacyServiceException.class);
        assertThat(SubjectIds.require("a".repeat(255))).hasSize(255);
        assertThatThrownBy(() -> SubjectIds.require("a".repeat(256))).isInstanceOf(PrivacyServiceException.class);
    }

    @Test
    void auditRef_whenComputed_isStableAndDoesNotSpellTheSub() {
        UUID ref = SubjectIds.auditRef("acct-ayse");
        assertThat(SubjectIds.auditRef("acct-ayse")).isEqualTo(ref);
        assertThat(SubjectIds.auditRef("acct-ayse2")).isNotEqualTo(ref);
        assertThat(ref.toString()).doesNotContain("ayse");
        assertThat(ref.version()).isEqualTo(3);
    }
}
