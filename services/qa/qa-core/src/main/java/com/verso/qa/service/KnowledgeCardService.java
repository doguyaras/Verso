package com.verso.qa.service;

import com.verso.document.api.dto.PageResponse;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.KnowledgeCardRequest;
import com.verso.qa.api.dto.KnowledgeCardResponse;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeCardSummary;
import java.util.UUID;

/**
 * /v1/admin/knowledge-cards (ADR-0049): verified answer cards, managed by {@code verso-admin} (the "bilgi sahibi" of
 * v1). The admin's identity comes from the validated token, never from the request.
 */
public interface KnowledgeCardService {

    /** A card from one of the admin's own sourced answers; DRAFT, or VERIFIED at once when {@code verify} is set. */
    KnowledgeCardResponse create(AccountId admin, KnowledgeCardRequest request);

    /** One page of cards, newest change first; {@code state} null for every state. */
    PageResponse<KnowledgeCardSummary> list(AccountId admin, KnowledgeCardState state, int page, int size);

    KnowledgeCardResponse get(AccountId admin, UUID cardId);

    /** Records the admin's verification; VERIFIED once the cycle has enough distinct verifiers. */
    KnowledgeCardResponse verify(AccountId admin, UUID cardId);

    KnowledgeCardResponse archive(AccountId admin, UUID cardId);
}
