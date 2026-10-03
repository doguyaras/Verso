package com.verso.qa.api.dto;

/**
 * What answers this instance gives (ADR-0006, ADR-0015): the AI mode and the model names, for the panel's system
 * screen and for clients that show which model answered. The same as /actuator/info, on the API port, with a token.
 *
 * @param mode           local or cloud (the X-Rag-Mode header)
 * @param chatModel      the chat model in use
 * @param embeddingModel the embedding model (always local)
 */
public record ServiceInfoResponse(String mode, String chatModel, String embeddingModel) {
}
