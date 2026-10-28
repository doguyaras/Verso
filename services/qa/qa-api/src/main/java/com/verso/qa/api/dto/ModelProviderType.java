package com.verso.qa.api.dto;

/**
 * The kind of model provider a connection talks to (ADR-0056). Two kinds can run on the installation's own network
 * ({@link #OLLAMA} and {@link #OPENAI_COMPATIBLE}: vLLM, llama.cpp's llama-server, a customer gateway); every other
 * kind is a cloud provider and is refused in local mode (llm-rules 1.1).
 */
public enum ModelProviderType {
    OPENAI(false),
    AZURE_OPENAI(false),
    ANTHROPIC(false),
    BEDROCK(false),
    GOOGLE_GENAI(false),
    MISTRAL(false),
    OLLAMA(true),
    OPENAI_COMPATIBLE(true);

    private final boolean selfHostable;

    ModelProviderType(boolean selfHostable) {
        this.selfHostable = selfHostable;
    }

    /** True when the provider may run on the installation's internal network (the only kinds local mode accepts). */
    public boolean selfHostable() {
        return selfHostable;
    }
}
