package com.verso.qa.config;

import java.util.Locale;

/** Where the chat model runs (ADR-0006). Embeddings are local in both modes (llm-rules 1.2). */
public enum AiMode {
    LOCAL,
    CLOUD;

    /** The value of the X-Rag-Mode header and of /actuator/info. */
    public String header() {
        return name().toLowerCase(Locale.ROOT);
    }
}
