package com.verso.qa.controller;

import com.verso.document.api.dto.PageResponse;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.ConversationResponse;
import com.verso.qa.api.dto.ConversationSummary;
import com.verso.qa.api.dto.FeedbackRequest;
import com.verso.qa.api.dto.RenameConversationRequest;
import com.verso.qa.api.dto.TurnFeedback;
import com.verso.qa.service.ConversationService;
import com.verso.qa.service.FeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/conversations (ADR-0023): the caller's own history and ratings. Binding only: the account comes from the token,
 * never from the path or the body; a path id is only the target, and another account's id is 404 (IDOR, reference
 * 6.5). Responses carry questions and answers: {@code Cache-Control: private, no-store}.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/conversations")
public class ConversationController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private final ConversationService conversations;
    private final FeedbackService feedback;

    public ConversationController(ConversationService conversations, FeedbackService feedback) {
        this.conversations = conversations;
        this.feedback = feedback;
    }

    /** Last activity first; at most 50 per page. */
    @GetMapping
    public ResponseEntity<PageResponse<ConversationSummary>> list(@CurrentAccount AccountId account,
                                                                  @RequestParam(name = "page", defaultValue = "0")
                                                                  @Min(0) @Max(100_000) int page,
                                                                  @RequestParam(name = "size", defaultValue = "20")
                                                                  @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(conversations.list(account, page, size));
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationResponse> get(@CurrentAccount AccountId account,
                                                    @PathVariable("conversationId") UUID conversationId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(conversations.get(account, conversationId));
    }

    @PatchMapping("/{conversationId}")
    public ResponseEntity<ConversationSummary> rename(@CurrentAccount AccountId account,
                                                      @PathVariable("conversationId") UUID conversationId,
                                                      @Valid @RequestBody RenameConversationRequest request) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(conversations.rename(account, conversationId, request.title()));
    }

    /** The conversation with its turns and ratings (KVKK erasure on request); the usage counts stay (anonymous). */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> delete(@CurrentAccount AccountId account,
                                       @PathVariable("conversationId") UUID conversationId) {
        conversations.delete(account, conversationId);
        return ResponseEntity.noContent().build();
    }

    /** Every conversation of the caller; idempotent (204 also when there was none). */
    @DeleteMapping
    public ResponseEntity<Void> deleteAll(@CurrentAccount AccountId account) {
        conversations.deleteAll(account);
        return ResponseEntity.noContent().build();
    }

    /** Rates one of the caller's answers; rating again replaces the earlier rating (idempotent per turn). */
    @PostMapping("/{conversationId}/turns/{turnId}/feedback")
    public ResponseEntity<TurnFeedback> rate(@CurrentAccount AccountId account,
                                             @PathVariable("conversationId") UUID conversationId,
                                             @PathVariable("turnId") UUID turnId,
                                             @Valid @RequestBody FeedbackRequest request) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(feedback.rate(account, conversationId, turnId, request));
    }
}
