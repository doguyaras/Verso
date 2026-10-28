package com.verso.qa.api.dto;

/**
 * What a model call is for (ADR-0056); every task has its own route: a primary model and an ordered fallback chain.
 * {@link #EMBEDDING} is listed so the panel can show it, but it is pinned to the local embedding model: changing it
 * means re-indexing every document (llm-rules 6.2), so the gateway refuses a route for it.
 */
public enum ModelTask {
    ANSWER,
    FOLLOW_UP,
    DECOMPOSITION,
    SKILL_CONDENSE,
    EMBEDDING
}
