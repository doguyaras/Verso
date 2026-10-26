package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The optional local-model suggestion pass of the system model (ADR-0054). Off by default: Turkish relation extraction
 * is weak in every model (ADR-0048), so the deterministic pass is the model and this pass may only propose DRAFT
 * relations between entities it already found, for an admin's review. Starting values (reference 1.4).
 *
 * @param suggestionsEnabled allow an admin to run the pass on one document (VERSO_QA_SYSTEM_MODEL_SUGGESTIONS_ENABLED);
 *                           in the local mode only, whatever this says
 * @param timeout            the one model call per run; on timeout nothing is stored
 * @param maxChunks          chunks of the document read per run (its first ones), within {@code maxPromptChars}
 * @param maxPromptChars     characters of document text in the prompt
 * @param maxSuggestions     suggestions stored per run at most
 */
@ConfigurationProperties("verso.qa.system-model")
public record QaSystemModelProperties(
        @DefaultValue("false") boolean suggestionsEnabled,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("12") int maxChunks,
        @DefaultValue("12000") int maxPromptChars,
        @DefaultValue("30") int maxSuggestions) {

    public QaSystemModelProperties {
        if (!timeout.isPositive() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("verso.qa.system-model.timeout must be 1ms..5m");
        }
        if (maxChunks < 1 || maxChunks > 50) {
            throw new IllegalStateException("verso.qa.system-model.max-chunks must be 1..50");
        }
        if (maxPromptChars < 1000 || maxPromptChars > 50_000) {
            throw new IllegalStateException("verso.qa.system-model.max-prompt-chars must be 1000..50000");
        }
        if (maxSuggestions < 1 || maxSuggestions > 200) {
            throw new IllegalStateException("verso.qa.system-model.max-suggestions must be 1..200");
        }
    }
}
