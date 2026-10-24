package com.verso.connector.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.connector.config.ConnectorProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/** Egress check and credential lookup (ADR-0038, security-rules 1 and 5). */
class EgressAndCredentialsTest {

    @Test
    void resolve_whenTheAddressIsOutsideTheAllowlist_isDeniedBeforeAnyConnection() {
        EgressGuard guard = new EgressGuard(properties("10.20.0.0/16"));

        assertThat(guard.resolve("10.20.1.2").getHostAddress()).isEqualTo("10.20.1.2");
        for (String host : new String[]{"10.21.0.1", "8.8.8.8", "127.0.0.1", "169.254.169.254", "::1", "localhost"}) {
            assertThatThrownBy(() -> guard.resolve(host)).as(host).isInstanceOf(SourceException.class)
                    .satisfies(e -> {
                        assertThat(((SourceException) e).code()).isEqualTo("EGRESS_DENIED");
                        assertThat(((SourceException) e).isFatal()).isTrue();
                    });
        }
    }

    @Test
    void resolve_whenTheAllowlistIsEmpty_deniesEverything() {
        EgressGuard guard = new EgressGuard(properties(""));

        assertThatThrownBy(() -> guard.resolve("10.20.1.2")).hasMessage("EGRESS_DENIED");
        assertThat(guard.allowsLiteral("10.20.1.2")).isFalse();
    }

    @Test
    void resolve_whenTheNameDoesNotResolve_isUnreachableNotAllowed() {
        EgressGuard guard = new EgressGuard(properties("10.0.0.0/8"));

        assertThatThrownBy(() -> guard.resolve("no-such-host.invalid")).hasMessage("SOURCE_UNREACHABLE");
    }

    @Test
    void password_whenTheNameIsNotAConnectorSecret_isRefused_soOtherSecretsCannotBeSentAway() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("SECRET_DB_DOCUMENT_PASSWORD", "db-secret-must-not-leave")
                .withProperty("SECRET_CONNECTOR_SMB_PASSWORD", "share-pass word\n");
        ConnectorCredentials credentials = new ConnectorCredentials(env);

        assertThatThrownBy(() -> credentials.password("SECRET_DB_DOCUMENT_PASSWORD")).hasMessage("CREDENTIAL_MISSING");
        assertThatThrownBy(() -> credentials.password("spring.datasource.password")).hasMessage("CREDENTIAL_MISSING");
        assertThatThrownBy(() -> credentials.password("SECRET_CONNECTOR_OTHER")).hasMessage("CREDENTIAL_MISSING");
        assertThat(new String(credentials.password("SECRET_CONNECTOR_SMB_PASSWORD")))
                .as("a trailing line break is dropped, inner spaces kept").isEqualTo("share-pass word");
    }

    @Test
    void sourceException_whenThrown_carriesTheCodeOnly() {
        SourceException e = new SourceException("SOURCE_AUTH_FAILED", true,
                new IllegalStateException("STATUS_LOGON_FAILURE for user svc-verso on fs01"));

        assertThat(e.getMessage()).isEqualTo("SOURCE_AUTH_FAILED");
        assertThat(e.causeType()).isEqualTo("IllegalStateException");
        assertThat(e.getStackTrace()).isEmpty();
    }

    @Test
    void properties_whenTheAllowlistNamesAPublicRange_failTheStart() {
        assertThatThrownBy(() -> properties("0.0.0.0/0")).isInstanceOf(IllegalArgumentException.class);
    }

    static ConnectorProperties properties(String allowed) {
        return new ConnectorProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(10), allowed, 50, 500,
                DataSize.ofGigabytes(2), 100_000, 32, Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofMinutes(1),
                Duration.ofMinutes(2), 20, 20, 3, 100,
                new ConnectorProperties.Smb(Duration.ofSeconds(10), Duration.ofSeconds(60), true, false), null, null, null);
    }
}
