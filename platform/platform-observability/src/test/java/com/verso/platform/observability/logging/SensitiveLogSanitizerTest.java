package com.verso.platform.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Sanitizer rules (reference 8.4 table). The cases marked "review" reproduce defects found by the 2026-10-02 review
 * in the code ported from the reference skeleton; each of them failed before the fix.
 */
class SensitiveLogSanitizerTest {

    @Test
    void sanitize_whenMixedSecrets_removesLineBreaksRedactsAndMasks() {
        String raw = "POST /hook\r\nAuthorization: Bearer abc.def token=SECRET-1 password: p4ss, otp=123456 "
                + "to=+905551234567 mail=jane.doe@example.com key=" + "A".repeat(40);
        String s = SensitiveLogSanitizer.sanitize(raw);
        assertThat(s).doesNotContain("\r").doesNotContain("\n")
                .contains("token=[REDACTED]").contains("password: [REDACTED]").contains("otp=[REDACTED]")
                .doesNotContain("SECRET-1").doesNotContain("p4ss").doesNotContain("123456")
                .doesNotContain("abc.def")
                .contains("+90********67").doesNotContain("905551234567")
                .contains("j***@e***.com").doesNotContain("jane.doe")
                .doesNotContain("A".repeat(40));
    }

    @Test
    void sanitize_whenLongOrNull_cutsAt240OrReturnsEmpty() {
        // Text with spaces is cut; an unbroken 32+ alphanumeric run is redacted as an opaque token.
        assertThat(SensitiveLogSanitizer.sanitize("word ".repeat(100)))
                .hasSize(SensitiveLogSanitizer.MAX_LENGTH + 3).endsWith("...");
        assertThat(SensitiveLogSanitizer.sanitize("x".repeat(500))).isEqualTo("[REDACTED]");
        assertThat(SensitiveLogSanitizer.sanitize(null)).isEmpty();
    }

    /** Review: JSON keys are quoted, so the value used to survive untouched. */
    @Test
    void sanitize_whenJsonSecretFields_redactsTheirValues() {
        String s = SensitiveLogSanitizer.sanitize("{\"password\":\"hunter2\",\"token\":\"tok-123\",\"api_key\":\"sk-short-1\"}");
        assertThat(s).doesNotContain("hunter2").doesNotContain("tok-123").doesNotContain("sk-short-1")
                .contains("\"password\":\"[REDACTED]");
    }

    /** Review: a bearer credential shorter than 32 characters used to stay readable. */
    @Test
    void sanitize_whenShortBearerOrBasicCredential_redactsIt() {
        assertThat(SensitiveLogSanitizer.sanitize("Authorization: Bearer shortTok-123"))
                .doesNotContain("shortTok-123");
        assertThat(SensitiveLogSanitizer.sanitize("header Basic dXNlcjpwYXNz trailing"))
                .doesNotContain("dXNlcjpwYXNz").contains("trailing");
    }

    /** Review: IP addresses must never be logged (reference 8.4). */
    @Test
    void sanitize_whenIpAddresses_replacesThem() {
        String s = SensitiveLogSanitizer.sanitize("connect to http://10.0.0.5:11434 from 192.168.1.20 via fe80:0:0:0:1:2:3:4");
        assertThat(s).doesNotContain("10.0.0.5").doesNotContain("192.168.1.20").doesNotContain("fe80:0:0:0")
                .contains("[IP]");
    }

    @Test
    void sanitize_whenClockTimes_keepsThem() {
        assertThat(SensitiveLogSanitizer.sanitize("timeout at 10:00:00 after 3 tries")).contains("10:00:00");
    }

    /** Review: user name and password in a URL used to be only partly masked by the e-mail pattern. */
    @Test
    void sanitize_whenUrlCredentials_redactsUserAndPassword() {
        String s = SensitiveLogSanitizer.sanitize("jdbc url postgres://admin:S3cretPass@db.internal:5432/verso");
        assertThat(s).doesNotContain("admin").doesNotContain("S3cretPass").contains("postgres://[REDACTED]@");
    }

    /** Review: a query string may carry search terms (document topics) or identifiers. */
    @Test
    void sanitize_whenUrlQuery_dropsIt() {
        String s = SensitiveLogSanitizer.sanitize("GET https://docs.example.com/search?q=maas+bordrosu&owner=42 failed");
        assertThat(s).doesNotContain("maas").doesNotContain("owner=42")
                .contains("https://docs.example.com/search?[REDACTED]").endsWith("failed");
    }

    /** Second-round review: prefixed and camelCase keys escaped the word-boundary pattern. */
    @Test
    void sanitize_whenSecretWordIsPartOfKey_redactsValue() {
        String s = SensitiveLogSanitizer.sanitize("accessToken=abc123 refreshToken: def456 client_secret=s3cr3tVal "
                + "db_password=hunter2x jwt_token=abcdEF12 private_api_key=k9 {\"accessToken\":\"jsonTok1\"}");
        assertThat(s).doesNotContain("abc123").doesNotContain("def456").doesNotContain("s3cr3tVal")
                .doesNotContain("hunter2x").doesNotContain("abcdEF12").doesNotContain("=k9").doesNotContain("jsonTok1");
    }

    @Test
    void sanitize_whenCookieHeader_redactsItsValue() {
        assertThat(SensitiveLogSanitizer.sanitize("Cookie: SESSION=abc123def; theme=dark"))
                .doesNotContain("abc123def");
    }

    /** Second-round review: a quoted value with spaces was only redacted up to the first space. */
    @Test
    void sanitize_whenQuotedSecretValueContainsSpaces_redactsTheWholeValue() {
        assertThat(SensitiveLogSanitizer.sanitize("{\"password\":\"horse battery staple\",\"user\":\"x\"}"))
                .doesNotContain("horse").doesNotContain("battery").doesNotContain("staple").contains("\"user\":\"x\"");
        assertThat(SensitiveLogSanitizer.sanitize("password=\"secret phrase here\" next=1"))
                .doesNotContain("secret phrase").doesNotContain("here").contains("next=1");
    }

    /** Second-round review: compressed IPv6 forms were not recognised. */
    @Test
    void sanitize_whenCompressedIpv6_replacesIt() {
        String s = SensitiveLogSanitizer.sanitize("peer 2001:db8::1 refused, loopback ::1, link fe80::a1b2 done");
        assertThat(s).doesNotContain("2001:db8").doesNotContain("::1").doesNotContain("fe80").contains("done");
    }

    @Test
    void sanitize_whenNonHttpUrlHasQueryOrPasswordWithAt_redactsThem() {
        assertThat(SensitiveLogSanitizer.sanitize("wss://push.example.com/ws?q=jane&x=1 closed"))
                .doesNotContain("jane").endsWith("closed");
        assertThat(SensitiveLogSanitizer.sanitize("ftp://user:pa@ss@files.example.com/x"))
                .doesNotContain("pa@ss").doesNotContain("ss@files").contains("[REDACTED]@files.example.com");
    }

    /**
     * Second-round review: the input cut used to leave half a token, half an address or half a number. It becomes
     * visible when a long opaque block before it shrinks to [REDACTED] and pulls the half token into the first 240
     * characters of the output (a plain-text prefix would hide it behind the final cut and prove nothing).
     */
    @Test
    void sanitize_whenCutFallsInsideSensitiveToken_dropsThePartialToken() {
        for (String tail : new String[]{"TOKNx9Y8z7W6v5U4t3S2r1Q0p9O8n7M6", "jane.doe@example.com", "+905551234567"}) {
            for (int inside = 6; inside < tail.length(); inside++) {
                // The cut at MAX_INPUT lands `inside` characters into the tail.
                String opaque = "A".repeat(SensitiveLogSanitizer.MAX_INPUT - inside - 1);
                String raw = opaque + " " + tail + " trailing text after the cut";
                String s = SensitiveLogSanitizer.sanitize(raw);
                assertThat(s).as("tail %s cut after %d chars", tail, inside)
                        .doesNotContain("TOKNx9").doesNotContain("jane.doe").doesNotContain("905551");
            }
        }
    }

    @Test
    void sanitize_whenIsoTimestamp_keepsItReadable() {
        assertThat(SensitiveLogSanitizer.sanitize("failed at 2026-10-02 13:45:00 after retry"))
                .contains("2026-10-02 13:45:00");
    }

    @Test
    void sanitize_whenTurkishText_keepsItReadableButCutsIt() {
        String turkish = "Kişisel verilerin işlenmesi şartları: ".repeat(20);
        String s = SensitiveLogSanitizer.sanitize(turkish);
        assertThat(s).startsWith("Kişisel verilerin işlenmesi").hasSize(SensitiveLogSanitizer.MAX_LENGTH + 3);
    }

    /** Review (security B3): the e-mail pattern was quadratic and ran before the cut (64k chars took ~10 s). */
    @Test
    void sanitize_whenHugeCraftedInput_finishesQuickly() {
        String crafted = "a.".repeat(500_000);
        String s = assertTimeoutPreemptively(Duration.ofMillis(500), () -> SensitiveLogSanitizer.sanitize(crafted));
        assertThat(s).hasSizeLessThanOrEqualTo(SensitiveLogSanitizer.MAX_LENGTH + 3);
        // Masking still works after the bound was introduced.
        assertThat(SensitiveLogSanitizer.sanitize("mail jane.doe@example.com")).contains("j***@e***.com");
    }

    @Test
    void maskPhone_whenNumberGiven_keepsCountryCodeAndLastTwoDigits() {
        assertThat(SensitiveLogSanitizer.maskPhone("+905551234567")).isEqualTo("+90********67");
        assertThat(SensitiveLogSanitizer.maskPhone("05551234567")).isEqualTo("0********67");
        assertThat(SensitiveLogSanitizer.maskPhone("123")).isEqualTo("***");
    }

    @Test
    void maskEmail_whenAddressGiven_keepsFirstCharAndTld() {
        assertThat(SensitiveLogSanitizer.maskEmail("jane.doe@example.com")).isEqualTo("j***@e***.com");
        assertThat(SensitiveLogSanitizer.maskEmail("bogus")).isEqualTo("***");
    }

    @Test
    void tokenFingerprint_whenTokenGiven_isShortStableSha256() {
        String fp = SensitiveLogSanitizer.tokenFingerprint("SECRET-TOKEN");
        assertThat(fp).startsWith("sha256:").hasSize("sha256:".length() + 12).doesNotContain("SECRET");
        assertThat(SensitiveLogSanitizer.tokenFingerprint("SECRET-TOKEN")).isEqualTo(fp);
        assertThat(SensitiveLogSanitizer.tokenFingerprint("OTHER")).isNotEqualTo(fp);
    }

    @Test
    void safeExceptionSummary_whenWrapped_usesRootCauseAndSanitizedMessage() {
        var root = new IllegalStateException("insert failed: Key (phone)=(+905551234567) token=SECRET-9");
        var wrapped = new RuntimeException("outer", new RuntimeException("middle", root));
        String s = SensitiveLogSanitizer.safeExceptionSummary(wrapped);
        assertThat(s).startsWith("IllegalStateException: ").contains("+90********67").contains("token=[REDACTED]")
                .doesNotContain("905551234567").doesNotContain("SECRET-9").doesNotContain("outer");
        assertThat(SensitiveLogSanitizer.safeExceptionSummary(new IllegalArgumentException()))
                .isEqualTo("IllegalArgumentException");
    }

    /** Third-round review B24: only Bearer/Basic were known; other schemes kept the credential after the scheme. */
    @Test
    void sanitize_whenAuthorizationUsesAnyScheme_redactsSchemeAndCredential() {
        String s = SensitiveLogSanitizer.sanitize("Authorization: DPoP shortTok3n next | Proxy-Authorization: Digest "
                + "username=bob7, realm=x | x-authorization=Token abc9xyz | {\"authorization\":\"DPoP jsonTok5\"}");
        assertThat(s).doesNotContain("shortTok3n").doesNotContain("bob7").doesNotContain("abc9xyz")
                .doesNotContain("jsonTok5").contains("next");
    }

    /** Third-round review B24: secret words missing from the key list. */
    @Test
    void sanitize_whenSaltPepperPrivateKeyOrShortPasswordKeys_redactsValues() {
        String s = SensitiveLogSanitizer.sanitize("salt=s4ltV pepper=p3ppr privateKey=pk1v private_key=pk2v "
                + "pass=hunter3 pw=pw9v done");
        assertThat(s).doesNotContain("s4ltV").doesNotContain("p3ppr").doesNotContain("pk1v").doesNotContain("pk2v")
                .doesNotContain("hunter3").doesNotContain("pw9v").contains("done");
    }

    /** Third-round review B24: a URL with an empty user name (redis://:password@host) kept its password. */
    @Test
    void sanitize_whenUrlHasPasswordWithoutUser_redactsIt() {
        assertThat(SensitiveLogSanitizer.sanitize("cache at redis://:onlyPass9@cache:6379/0 down"))
                .doesNotContain("onlyPass9").contains("redis://[REDACTED]@");
    }

    /** Third-round review B24: an escaped quote ended the quoted value early and left its tail visible. */
    @Test
    void sanitize_whenQuotedSecretContainsEscapedQuote_redactsTheWholeValue() {
        assertThat(SensitiveLogSanitizer.sanitize("{\"password\":\"ab\\\"c secretTail9\",\"user\":\"u1\"}"))
                .doesNotContain("secretTail9").contains("\"user\"");
    }

    /** Third-round review B24: Unicode line separators and terminal escapes allowed log forging. */
    @Test
    void sanitize_whenUnicodeLineSeparatorsOrControlCharacters_replacesThem() {
        String s = SensitiveLogSanitizer.sanitize("a forged b\u0085c\u001b[31mred\u0000end\u000bz");
        assertThat(s).doesNotContain(" ").doesNotContain(" ").doesNotContain("\u0085")
                .doesNotContain("\u001b").doesNotContain("\u0000").doesNotContain("\u000b").contains("forged");
    }

    /** Third-round test review (J09-J11): rules that no test pinned. */
    @Test
    void sanitize_whenTenDigitPhoneSessionCookieOr32CharToken_hidesThem() {
        assertThat(SensitiveLogSanitizer.sanitize("callback to 5551234567 failed")).doesNotContain("5551234567");
        assertThat(SensitiveLogSanitizer.sanitize("JSESSIONID=9f8e7d6c5b4a; path=/")).doesNotContain("9f8e7d6c5b4a");
        // base64 on purpose: a digit-heavy hex string would be masked by the phone rule first. Built at run time so
        // the source holds no key-shaped literal (the repository's own gitleaks scan flagged the literal).
        String token32 = java.util.Base64.getEncoder()
                .encodeToString("fake-api-key-for-testing".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(token32).hasSize(32);
        assertThat(SensitiveLogSanitizer.sanitize("digest " + token32 + " done")).doesNotContain(token32).contains("done");
    }

    @Test
    void rootCauseType_whenWrapped_returnsDeepestCauseSimpleName() {
        var wrapped = new RuntimeException("outer", new IllegalStateException("middle", new java.io.IOException("x")));
        assertThat(SensitiveLogSanitizer.rootCauseType(wrapped)).isEqualTo("IOException");
        assertThat(SensitiveLogSanitizer.rootCauseType(null)).isEmpty();
    }

    /** Review (security B5): Throwable.initCause allows A -> B -> A, which used to loop forever. */
    @Test
    void rootCauseType_whenCauseChainIsCyclic_terminates() {
        var a = new IllegalStateException("a");
        var b = new IllegalArgumentException("b", a);
        a.initCause(b);
        String type = assertTimeoutPreemptively(Duration.ofMillis(500), () -> SensitiveLogSanitizer.rootCauseType(a));
        assertThat(type).isIn("IllegalStateException", "IllegalArgumentException");
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> SensitiveLogSanitizer.safeExceptionSummary(b));
    }
}
