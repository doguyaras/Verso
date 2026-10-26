package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.verso.document.api.DocumentSystemModel;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SystemEntitySummary;
import com.verso.document.api.enums.SourceUnit;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaSystemModelProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.SystemSuggestionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/** The optional suggestion pass of ADR-0054: off by default, and never trusted when on. */
class SystemModelSuggestionsTest {

    private static final List<SystemEntitySummary> ENTITIES = List.of(
            new SystemEntitySummary(SystemEntityType.PROCESS, "iade sureci", "İade Süreci", 1, 2),
            new SystemEntitySummary(SystemEntityType.ROLE, "depo sorumlusu", "Depo Sorumlusu", 1, 1),
            new SystemEntitySummary(SystemEntityType.CODE, "WMS-204", "WMS-204", 1, 1),
            new SystemEntitySummary(SystemEntityType.SYSTEM, "WMS", "WMS", 1, 1));
    private static final List<SourceChunk> CHUNKS = List.of(
            chunk("İade Süreci: depo sorumlusu her iadeyi kontrol eder."),
            chunk("WMS ekranında WMS-204 görülürse sayım yapılır."));

    private static SourceChunk chunk(String text) {
        return new SourceChunk(UUID.randomUUID(), UUID.randomUUID(), 1, SourceUnit.PAGE, "a".repeat(64), null, null,
                null, text);
    }

    private static SystemModelSuggestions suggestions(boolean enabled, AiMode mode, ChatModel chat,
                                                      DocumentSystemModel model) {
        return new SystemModelSuggestions(chat, new VersoAiProperties(mode, "m", "e"),
                new QaSystemModelProperties(enabled, Duration.ofSeconds(5), 12, 12_000, 30), model,
                mock(SystemSuggestionRepository.class), mock(QaDatabase.class), mock(QaAudit.class),
                Clock.systemUTC());
    }

    @Test
    void run_whenSwitchedOff_isRefusedWithoutAnyModelOrDocumentCall() {
        ChatModel chat = mock(ChatModel.class);
        DocumentSystemModel model = mock(DocumentSystemModel.class);
        SystemModelSuggestions off = suggestions(false, AiMode.LOCAL, chat, model);

        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.run(Reader.personal("admin"), UUID.randomUUID()))
                .isInstanceOfSatisfying(QaServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QaErrorCode.SYSTEM_SUGGESTIONS_DISABLED));
        verifyNoInteractions(chat, model);
    }

    @Test
    void run_whenOnButCloudMode_isRefusedToo() {
        ChatModel chat = mock(ChatModel.class);
        SystemModelSuggestions cloud = suggestions(true, AiMode.CLOUD, chat, mock(DocumentSystemModel.class));

        assertThat(cloud.enabled()).isFalse();
        assertThatThrownBy(() -> cloud.run(Reader.personal("admin"), UUID.randomUUID()))
                .isInstanceOf(QaServiceException.class);
        verifyNoInteractions(chat);
    }

    @Test
    void defaults_whenNothingConfigured_keepThePassOff() {
        QaSystemModelProperties defaults = new QaSystemModelProperties(false, Duration.ofSeconds(60), 12, 12_000, 30);

        assertThat(defaults.suggestionsEnabled()).isFalse();
    }

    @Test
    void parse_keepsOnlyClosedSchemaLinesBetweenEntitiesWrittenInTheCitedChunk() {
        SystemModelSuggestions.Parsed parsed = SystemModelSuggestions.parse("""
                OWNED_BY|1|2|1
                CODE_OF|3|4|2
                CODE_OF|3|4|1
                OWNED_BY|2|1|1
                DEFINES|3|4|2
                PART_OF|9|4|2
                Elbette, işte ilişkiler:
                OWNED_BY|1|2|1""", ENTITIES, CHUNKS);

        assertThat(parsed.valid()).containsExactly(new SystemModelSuggestions.Proposal("OWNED_BY", 0, 1, 0),
                new SystemModelSuggestions.Proposal("CODE_OF", 2, 3, 1));
        // Wrong chunk, reversed types, an unknown relation, an unknown entity and chatter are rejected.
        assertThat(parsed.rejected()).isEqualTo(5);
        assertThat(parsed.proposed()).isEqualTo(8);
    }

    @Test
    void prompt_whenBuilt_holdsLabelsAndChunkTextOnly() {
        String prompt = SystemModelSuggestions.prompt(ENTITIES, CHUNKS);

        assertThat(prompt).contains("1|PROCESS|İade Süreci", "4|SYSTEM|WMS", "--- parça 2 ---");
        assertThat(SystemModelSuggestions.SYSTEM).contains("Kişi adı yazma");
    }
}
