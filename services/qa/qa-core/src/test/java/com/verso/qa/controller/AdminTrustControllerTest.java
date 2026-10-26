package com.verso.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The pure parts of the trust report (ADR-0050): only well-formed values pass, ages never go negative. */
class AdminTrustControllerTest {

    @Test
    void digest_acceptsOnlyAWellFormedSha256() {
        String good = "sha256:" + "a1".repeat(32);
        assertThat(AdminTrustController.digest(" " + good + " ")).isEqualTo(good);
        assertThat(AdminTrustController.digest("sha256:abc")).isNull();
        assertThat(AdminTrustController.digest("sha256:" + "A1".repeat(32))).as("lower case hex only").isNull();
        assertThat(AdminTrustController.digest("ghcr.io/x/verso@" + good)).as("a reference is not a digest").isNull();
        assertThat(AdminTrustController.digest("<script>")).isNull();
        assertThat(AdminTrustController.digest(null)).isNull();
    }

    @Test
    void ageSeconds_isNullWithoutARowAndNeverNegative() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        assertThat(AdminTrustController.ageSeconds(null, now)).isNull();
        assertThat(AdminTrustController.ageSeconds(now.minusSeconds(90), now)).isEqualTo(90L);
        assertThat(AdminTrustController.ageSeconds(now.plusSeconds(5), now)).as("clock skew").isZero();
    }

    @Test
    void guarantee_textsSayWhatStaysAndWhatLeaves() {
        assertThat(AdminTrustController.GUARANTEE_LOCAL).startsWith("Yerel mod:").contains("bu sunucudan çıkmaz");
        assertThat(AdminTrustController.GUARANTEE_CLOUD).startsWith("Bulut modu:").contains("en çok 5 pasaj");
    }
}
