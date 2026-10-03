package com.verso.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The AI mode (ADR-0006: a property, not a Spring profile) and the model names reported with every answer and on
 * /actuator/info. The names are read from the model clients' own settings in config/verso.yml (the chat model from the
 * settings of the provider in spring.ai.model.chat). AiModeCheck verifies that the settings keep the mode's promise.
 *
 * @param mode           local (no outbound connection, llm-rules 1.1) or cloud (only the chat call leaves)
 * @param chatModel      the chat model in use
 * @param embeddingModel the embedding model in use (always local)
 */
@ConfigurationProperties("verso.ai")
public record VersoAiProperties(@DefaultValue("local") AiMode mode, String chatModel, String embeddingModel) {

    public VersoAiProperties {
        if (chatModel == null || chatModel.isBlank()) throw new IllegalStateException("verso.ai.chat-model is required");
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalStateException("verso.ai.embedding-model is required");
        }
    }
}
