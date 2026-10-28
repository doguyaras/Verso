package com.verso.qa.api.dto;

import java.util.List;

/**
 * What answers this instance gives (ADR-0006, ADR-0015): the AI mode and the model names, for the panel's system
 * screen and for clients that show which model answered. The same as /actuator/info, on the API port, with a token.
 * Also the caller's own Verso roles (ADR-0019), so that a client can hide what the caller cannot use; the server
 * checks the roles itself on every request.
 *
 * @param mode           local or cloud (the X-Rag-Mode header)
 * @param chatModel      the chat model in use
 * @param embeddingModel the embedding model (always local)
 * @param roles          the caller's Verso roles, highest first: {@code verso-admin}, {@code verso-user}; every
 *                       signed-in caller has {@code verso-user}. Clients ignore values they do not know.
 * @param staleAfterDays the age in days after which a cited document counts as stale (ADR-0052's threshold), for the
 *                       answer screen's "Bu kaynak eskimiş" card (ADR-0058); null when unknown (additive field)
 */
public record ServiceInfoResponse(String mode, String chatModel, String embeddingModel, List<String> roles,
                                  Integer staleAfterDays) {

    public ServiceInfoResponse {
        roles = List.copyOf(roles);
    }

    /** Without the stale threshold (before ADR-0058). */
    public ServiceInfoResponse(String mode, String chatModel, String embeddingModel, List<String> roles) {
        this(mode, chatModel, embeddingModel, roles, null);
    }
}
