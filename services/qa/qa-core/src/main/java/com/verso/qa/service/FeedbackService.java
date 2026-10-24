package com.verso.qa.service;

import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.FeedbackRequest;
import com.verso.qa.api.dto.TurnFeedback;
import java.util.UUID;

/** Rating of one of the caller's own answers (ADR-0023); rating again replaces the earlier rating. */
public interface FeedbackService {

    TurnFeedback rate(AccountId account, UUID conversationId, UUID turnId, FeedbackRequest request);
}
