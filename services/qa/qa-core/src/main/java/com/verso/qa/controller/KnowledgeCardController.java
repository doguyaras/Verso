package com.verso.qa.controller;

import com.verso.document.api.dto.PageResponse;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.KnowledgeCardRequest;
import com.verso.qa.api.dto.KnowledgeCardResponse;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeCardSummary;
import com.verso.qa.api.dto.KnowledgeEvalRunResponse;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.KnowledgeCardService;
import com.verso.qa.service.KnowledgeEval;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/knowledge-cards and /v1/admin/knowledge-eval (ADR-0049): the answer memory and its golden-set check,
 * {@code verso-admin} only (ADR-0019; the "bilgi sahibi" of v1); every other caller gets 403 ACCESS_DENIED. The admin
 * comes from the validated token. Responses are never cached.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class KnowledgeCardController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private final KnowledgeCardService cards;
    private final KnowledgeEval eval;

    public KnowledgeCardController(KnowledgeCardService cards, KnowledgeEval eval) {
        this.cards = cards;
        this.eval = eval;
    }

    /** A card from one of the caller's own answers; {@code verify: true} also records the caller's verification. */
    @PostMapping("/knowledge-cards")
    public ResponseEntity<KnowledgeCardResponse> create(@CurrentAccount AccountId admin,
                                                        @Valid @RequestBody KnowledgeCardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(PRIVATE).body(cards.create(admin, request));
    }

    /** Newest change first; {@code state} filters (DRAFT, VERIFIED, STALE, DISPUTED, ARCHIVED). */
    @GetMapping("/knowledge-cards")
    public ResponseEntity<PageResponse<KnowledgeCardSummary>> list(
            @CurrentAccount AccountId admin,
            @RequestParam(name = "state", required = false) KnowledgeCardState state,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(cards.list(admin, state, page, size));
    }

    /** One card with its citations, staleness reason, dispute counts per day and state history. */
    @GetMapping("/knowledge-cards/{cardId}")
    public ResponseEntity<KnowledgeCardResponse> get(@CurrentAccount AccountId admin,
                                                     @PathVariable("cardId") UUID cardId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(cards.get(admin, cardId));
    }

    @PostMapping("/knowledge-cards/{cardId}/verify")
    public ResponseEntity<KnowledgeCardResponse> verify(@CurrentAccount AccountId admin,
                                                        @PathVariable("cardId") UUID cardId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(cards.verify(admin, cardId));
    }

    @PostMapping("/knowledge-cards/{cardId}/archive")
    public ResponseEntity<KnowledgeCardResponse> archive(@CurrentAccount AccountId admin,
                                                         @PathVariable("cardId") UUID cardId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(cards.archive(admin, cardId));
    }

    /** The golden-set check runs, newest first. */
    @GetMapping("/knowledge-eval/runs")
    public ResponseEntity<PageResponse<KnowledgeEvalRunResponse>> runs(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(eval.runs(page, size));
    }

    /** Runs the check now (no model call); 409 while another run holds the lock. Synchronous: bounded by max-cases. */
    @PostMapping("/knowledge-eval/runs")
    public ResponseEntity<KnowledgeEvalRunResponse> run() {
        KnowledgeEvalRunResponse run = eval.run("MANUAL")
                .orElseThrow(() -> new QaServiceException(QaErrorCode.KNOWLEDGE_EVAL_RUNNING, "KNOWLEDGE_EVAL_RUNNING"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(PRIVATE).body(run);
    }
}
