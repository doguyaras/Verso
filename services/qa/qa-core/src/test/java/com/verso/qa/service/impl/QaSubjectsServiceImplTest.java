package com.verso.qa.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.qa.api.QaSubjects;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0034: the redaction notice lives in the database function (so the application cannot choose the text); the
 * contract's constant must be the same text, or the export and the tests would describe something else.
 */
class QaSubjectsServiceImplTest {

    @Test
    void redactedAnswer_whenCompared_equalsTheNoticeOfTheDatabaseFunction() throws IOException {
        String migration;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/qa/V2__answer_redaction.sql")) {
            assertThat(in).as("V2__answer_redaction.sql on the classpath").isNotNull();
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(migration).contains("notice constant text := '" + QaSubjects.REDACTED_ANSWER + "';");
        assertThat(QaSubjects.REDACTED_ANSWER).doesNotContain("'").hasSizeLessThan(200);
    }

    @Test
    void redaction_whenGivenTooManyIdsOrNone_isBoundedBeforeAnyStatement() {
        QaSubjectsServiceImpl service = new QaSubjectsServiceImpl(null, null);
        assertThat(service.redactAnswersCiting(java.util.List.of())).isZero();
        java.util.List<UUID> many = IntStream.rangeClosed(0, QaSubjects.MAX_REDACTION_IDS)
                .mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> service.redactAnswersCiting(many)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conversations_whenTheBatchIsOutOfBounds_isRefused() {
        QaSubjectsServiceImpl service = new QaSubjectsServiceImpl(null, null);
        assertThatThrownBy(() -> service.conversations("s", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.conversations("s", null, QaSubjects.MAX_CONVERSATION_BATCH + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
