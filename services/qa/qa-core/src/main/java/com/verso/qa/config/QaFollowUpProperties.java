package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Follow-up questions (ADR-0046): a question asked in a conversation that does not stand on its own ("Peki P2 için?")
 * is rewritten by the chat model, from the last turns of the same conversation, into one standalone search query.
 * Retrieval uses that query; the answer prompt keeps the question as asked. Starting values, measured on the follow-up
 * set (eval/followup-set.json, docs/evidence/takip-sorulari-olcumu.md).
 *
 * @param enabled           off: every question is searched as asked and the history never reaches a model (v1)
 * @param turns             how many of the latest turns of the conversation the rewrite sees
 * @param maxQuestionChars  characters of one earlier question in the rewrite prompt
 * @param maxAnswerChars    characters of the latest earlier answer in the rewrite prompt (answered turns only; 0: none)
 * @param maxHistoryChars   characters of the whole conversation part of the rewrite prompt; the oldest turns go first
 * @param maxQueryChars     a longer model output is not a search query: the question is searched as asked
 * @param timeout           one rewrite call; after it the question is searched as asked
 * @param answerWithContext the answer prompt also gets the rewritten query next to the question as asked
 */
@ConfigurationProperties("verso.qa.followup")
public record QaFollowUpProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2") int turns,
        @DefaultValue("300") int maxQuestionChars,
        @DefaultValue("200") int maxAnswerChars,
        @DefaultValue("1000") int maxHistoryChars,
        @DefaultValue("300") int maxQueryChars,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("false") boolean answerWithContext) {

    public QaFollowUpProperties {
        if (turns < 1 || turns > 5) throw new IllegalStateException("verso.qa.followup.turns must be between 1 and 5");
        if (maxQuestionChars < 50) throw new IllegalStateException("verso.qa.followup.max-question-chars must be at least 50");
        if (maxAnswerChars < 0) throw new IllegalStateException("verso.qa.followup.max-answer-chars must not be negative");
        if (maxHistoryChars < maxQuestionChars) {
            throw new IllegalStateException("verso.qa.followup.max-history-chars must be at least max-question-chars");
        }
        if (maxQueryChars < 50) throw new IllegalStateException("verso.qa.followup.max-query-chars must be at least 50");
        if (timeout == null || !timeout.isPositive()) {
            throw new IllegalStateException("verso.qa.followup.timeout must be positive");
        }
    }
}
