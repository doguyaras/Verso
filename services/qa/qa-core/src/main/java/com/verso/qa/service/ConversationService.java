package com.verso.qa.service;

import com.verso.document.api.dto.PageResponse;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.ConversationResponse;
import com.verso.qa.api.dto.ConversationSummary;
import java.util.UUID;

/** /v1/conversations (ADR-0023): the caller's own history; another account's conversation does not exist for them. */
public interface ConversationService {

    PageResponse<ConversationSummary> list(AccountId account, int page, int size);

    ConversationResponse get(AccountId account, UUID conversationId);

    ConversationSummary rename(AccountId account, UUID conversationId, String title);

    void delete(AccountId account, UUID conversationId);

    /** Every conversation of the caller, with their turns, ratings and activity rows; returns how many. */
    int deleteAll(AccountId account);
}
