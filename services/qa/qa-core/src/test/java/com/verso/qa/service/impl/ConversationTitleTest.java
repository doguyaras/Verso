package com.verso.qa.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A new conversation's title from its first question (ADR-0023): one line, bounded, no control or bidi characters. */
class ConversationTitleTest {

    @Test
    void title_whenTheQuestionIsShort_isTheQuestionOnOneLine() {
        assertThat(ConversationServiceImpl.title("  Yıllık izin\n kaç gün?  ")).isEqualTo("Yıllık izin kaç gün?");
    }

    @Test
    void title_whenTheQuestionIsLong_isCutAt80CodePoints() {
        String title = ConversationServiceImpl.title("ş".repeat(200));
        assertThat(title).endsWith("…");
        assertThat(title.codePointCount(0, title.length())).isEqualTo(80);
        String emoji = ConversationServiceImpl.title("😀".repeat(100));
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(80);
        assertThat(emoji).as("no surrogate pair cut in half").isEqualTo("😀".repeat(79) + "…");
    }

    @Test
    void title_whenTheQuestionCarriesBidiOverrides_dropsThem() {
        assertThat(ConversationServiceImpl.title("abc‮def⁦")).isEqualTo("abc def");
    }
}
