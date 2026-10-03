package com.verso.platform.core.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceExceptionTest {

    @Test
    void message_whenCauseGiven_comesFromCodeNotCause() {
        var cause = new IllegalStateException("Key (title)=(Ahmet_maas_bordrosu.pdf) already exists");
        var ex = new ServiceException(CommonErrorCode.INTERNAL_ERROR, "DUPLICATE_KEY", cause);

        assertThat(ex.getMessage()).isEqualTo("Unexpected error.");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getSafeLogReason()).isEqualTo("DUPLICATE_KEY");
    }

    @Test
    void details_whenSourceListChanges_stayCopiedAndImmutable() {
        List<String> source = new ArrayList<>(List.of("retryAfterSeconds=5"));
        var ex = new ServiceException(CommonErrorCode.VALIDATION, "R", "C", source);
        source.add("leaked=true");

        assertThat(ex.getDetails()).containsExactly("retryAfterSeconds=5");
        assertThatThrownBy(() -> ex.getDetails().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void details_whenNull_becomeEmpty() {
        assertThat(new ServiceException(CommonErrorCode.VALIDATION, null, null, null).getDetails()).isEmpty();
        assertThat(new ServiceException(CommonErrorCode.VALIDATION).getDetails()).isEmpty();
    }

    @Test
    void commonCodes_whenListed_followMessageFormatAndBlocks() {
        for (CommonErrorCode code : CommonErrorCode.values()) {
            assertThat(code.getMessage()).endsWith(".");
            int[] block = switch (code.getService()) {
                case "validation" -> new int[]{90000, 90099};
                case "security" -> new int[]{90100, 90199};
                case "system" -> new int[]{99997, 99999};
                default -> throw new AssertionError("unexpected service " + code.getService());
            };
            assertThat(code.getCode()).isBetween(block[0], block[1]);
        }
    }
}
