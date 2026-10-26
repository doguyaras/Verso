package com.verso.qa.service.impl;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.dto.Reader;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerOutcome;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.api.dto.ConversationResponse;
import com.verso.qa.api.dto.ConversationSummary;
import com.verso.qa.api.dto.FeedbackRating;
import com.verso.qa.api.dto.FeedbackReason;
import com.verso.qa.api.dto.HistoryCitation;
import com.verso.qa.api.dto.TurnFeedback;
import com.verso.qa.api.dto.TurnResponse;
import com.verso.qa.config.QaHistoryProperties;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.ConversationRepository;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.ConversationRepository.ConversationRow;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.TurnRepository;
import com.verso.qa.repository.TurnRepository.CitationRow;
import com.verso.qa.repository.TurnRepository.NewTurn;
import com.verso.qa.repository.TurnRepository.TurnRow;
import com.verso.qa.repository.UsageRepository;
import com.verso.qa.service.ConversationHistory;
import com.verso.qa.service.ConversationService;
import com.verso.qa.service.LatencyBuckets;
import com.verso.qa.service.QaAudit;
import com.verso.qa.service.QaMetrics;
import com.verso.qa.service.QuestionNormalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Conversation history (ADR-0023): stores every answered question with its citation references and timings, and
 * serves the caller's own conversations. Logs carry ids and counts only, never a question, an answer or a title
 * (llm-rules 2.1). Every transaction is short and holds no model call (llm-rules 5.1): the turn is stored after the
 * answer exists.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConversationServiceImpl implements ConversationService, ConversationHistory {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    /** The title a new conversation gets from its first question. */
    private static final int TITLE_CHARS = 80;
    /** The CHECK of qa.turn.answer; model answers are far shorter (num-predict 512 / max-tokens 2048). */
    private static final int MAX_ANSWER_CHARS = 32000;
    /** Conversations per delete statement when the caller deletes all of theirs (short transactions). */
    private static final int DELETE_BATCH = 200;

    private final QaDatabase db;
    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final UsageRepository usage;
    private final DocumentCatalog catalog;
    private final QaHistoryProperties properties;
    private final UsageMetricsProperties metricsProperties;
    private final QaMetrics metrics;
    private final Clock clock;
    private final CallerGroups groups;
    private final QaAudit audit;
    private final KnowledgeCardRepository knowledge;

    public ConversationServiceImpl(QaDatabase db, ConversationRepository conversations, TurnRepository turns,
                                   UsageRepository usage, DocumentCatalog catalog, QaHistoryProperties properties,
                                   UsageMetricsProperties metricsProperties, QaMetrics metrics, Clock clock,
                                   CallerGroups groups, QaAudit audit, KnowledgeCardRepository knowledge) {
        this.knowledge = knowledge;
        this.groups = groups;
        this.audit = audit;
        this.db = db;
        this.conversations = conversations;
        this.turns = turns;
        this.usage = usage;
        this.catalog = catalog;
        this.properties = properties;
        this.metricsProperties = metricsProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void checkWritable(AccountId account, UUID conversationId) {
        ConversationRow conversation = conversations.find(account.value(), conversationId)
                .orElseThrow(() -> new QaServiceException(QaErrorCode.CONVERSATION_NOT_FOUND, "CONVERSATION_NOT_FOUND"));
        if (conversation.turnCount() >= properties.maxTurnsPerConversation()) {
            throw new QaServiceException(QaErrorCode.CONVERSATION_FULL, "CONVERSATION_FULL");
        }
    }

    @Override
    public List<PriorTurn> recent(AccountId account, UUID conversationId, int count) {
        List<TurnRepository.RecentTurn> rows;
        try {
            rows = turns.recent(account.value(), conversationId, count);
        } catch (DataAccessException e) {
            // The rewrite is best effort (ADR-0046): the question is then searched as asked. Type only (llm-rules 2.1).
            log.warn("Conversation history not read for a follow-up: reason=DATABASE exceptionType={}",
                    e.getClass().getSimpleName());
            return List.of();
        }
        List<PriorTurn> oldestFirst = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            TurnRepository.RecentTurn row = rows.get(i);
            oldestFirst.add(new PriorTurn(row.question(), row.answer(), AnswerOutcome.ANSWERED.name().equals(row.outcome())));
        }
        return oldestFirst;
    }

    @Override
    public Optional<StoredTurn> record(AccountId account, UUID conversationId, String question, AnswerResponse answer,
                                       Timings timings) {
        StoredTurn stored;
        try {
            stored = db.inTransaction(status -> store(account.value(), conversationId, question, answer, timings));
        } catch (DataAccessException | TransactionException e) {
            // The answer is returned anyway; the turn is lost. Type only: a constraint message could quote the row.
            log.error("Turn not stored: code=HISTORY_WRITE_FAILED exceptionType={}", e.getClass().getSimpleName());
            metrics.turnNotStored("failed");
            return Optional.empty();
        }
        if (stored == null) {
            // Another answer filled the conversation after checkWritable (the limit is exact under the row lock).
            log.warn("Turn not stored: reason=CONVERSATION_FULL conversationId={}", conversationId);
            metrics.turnNotStored("conversation_full");
            return Optional.empty();
        }
        log.info("Turn stored: conversationId={} turnId={} outcome={}", stored.conversationId(), stored.turnId(),
                answer.outcome().name().toLowerCase(java.util.Locale.ROOT));
        return Optional.of(stored);
    }

    /** One transaction: conversation (locked or new), turn, citations and every rollup of the day. */
    private StoredTurn store(String account, UUID requested, String asked, AnswerResponse answer, Timings timings) {
        Instant now = clock.instant();
        String question = storable(asked);
        UUID conversationId = null;
        if (requested != null) {
            Optional<ConversationRow> locked = conversations.lock(account, requested);
            // A conversation lives retention-days from its start (HistoryRetentionJob): a turn added later would be
            // deleted with it within the hour, so it starts a new conversation instead.
            boolean expired = locked.isPresent()
                    && locked.get().createdAt().isBefore(now.minus(Duration.ofDays(properties.retentionDays())));
            if (locked.isPresent() && !expired) {
                if (turns.countInConversation(requested) >= properties.maxTurnsPerConversation()) return null;
                conversations.touch(requested, now);
                conversationId = requested;
            }
            // Deleted since checkWritable (another tab) or expired: a new conversation, whose id the answer carries.
        }
        if (conversationId == null) conversationId = conversations.insert(account, title(question), now).id();
        LocalDate day = LocalDate.ofInstant(now, metricsProperties.zone());
        String outcome = answer.outcome().name();
        UUID turnId = turns.insert(new NewTurn(conversationId, account, question, QuestionNormalizer.hash(question),
                bounded(storable(answer.answer())), outcome, answer.mode(), answer.model(), millis(timings.retrievalMs()),
                millis(timings.chatMs()), millis(timings.totalMs()), day, now));
        for (Citation citation : answer.citations()) {
            turns.insertCitation(turnId, citation.number(), citation.documentId(), citation.page(), citation.unit().name());
        }
        // ADR-0049: a down rating of this turn is then a dispute of the card that answered it.
        if (answer.memory() != null) knowledge.linkTurn(turnId, answer.memory().cardId());
        boolean firstToday = usage.markActive(day, account);
        usage.addQuestion(day, outcome, answer.mode(), firstToday);
        usage.addLatency(day, LatencyBuckets.bucketFor(timings.totalMs()));
        Map<UUID, Long> perDocument = answer.citations().stream()
                .collect(Collectors.groupingBy(Citation::documentId, LinkedHashMap::new, Collectors.counting()));
        perDocument.forEach((document, count) -> usage.addCitation(day, document, count.intValue()));
        // The audit record commits with the turn (ADR-0039).
        audit.questionStoredInTransaction(conversationId, turnId, answer);
        return new StoredTurn(conversationId, turnId);
    }

    @Override
    public PageResponse<ConversationSummary> list(AccountId account, int page, int size) {
        List<ConversationSummary> rows = conversations.list(account.value(), page, size).stream()
                .map(ConversationServiceImpl::summary).toList();
        return PageResponse.of(rows, page, size, conversations.count(account.value()));
    }

    @Override
    public ConversationResponse get(AccountId account, UUID conversationId) {
        ConversationRow conversation = conversations.find(account.value(), conversationId)
                .orElseThrow(() -> new QaServiceException(QaErrorCode.CONVERSATION_NOT_FOUND, "CONVERSATION_NOT_FOUND"));
        List<TurnRow> rows = turns.list(account.value(), conversationId);
        Map<UUID, List<CitationRow>> citations = turns.citations(account.value(), conversationId).stream()
                .collect(Collectors.groupingBy(CitationRow::turnId));
        // Resolved with the reader's access now, not at answer time (ADR-0028): a deleted document is absent (marked
        // deleted), one the reader no longer reaches comes back without its name (marked not accessible).
        Map<UUID, DocumentAccess> access = catalog.access(new Reader(account.value(), groups.current()),
                citations.values().stream().flatMap(List::stream).map(CitationRow::documentId).distinct().toList());
        List<TurnResponse> responses = new ArrayList<>(rows.size());
        for (TurnRow row : rows) {
            List<HistoryCitation> cited = citations.getOrDefault(row.id(), List.of()).stream()
                    .map(c -> historyCitation(c, access.get(c.documentId())))
                    .toList();
            TurnFeedback feedback = row.rating() == null ? null : new TurnFeedback(FeedbackRating.valueOf(row.rating()),
                    row.reason() == null ? null : FeedbackReason.valueOf(row.reason()), row.citationNumber(), row.ratedAt());
            responses.add(new TurnResponse(row.id(), row.question(), row.answer(), AnswerOutcome.valueOf(row.outcome()),
                    row.mode(), row.chatModel(), cited, feedback, row.createdAt(), row.totalMs()));
        }
        return new ConversationResponse(conversation.id(), conversation.title(), conversation.createdAt(),
                conversation.updatedAt(), responses);
    }

    private static HistoryCitation historyCitation(CitationRow c, DocumentAccess access) {
        CitationUnit unit = CitationUnit.valueOf(c.unit());
        if (access == null) return new HistoryCitation(c.number(), c.documentId(), null, c.page(), unit, true, false, null, null);
        return new HistoryCitation(c.number(), c.documentId(), access.fileName(), c.page(), unit, false,
                access.accessible(), access.collectionId(), access.collectionName());
    }

    @Override
    public ConversationSummary rename(AccountId account, UUID conversationId, String title) {
        ConversationRow renamed = conversations.rename(account.value(), conversationId, title.strip())
                .orElseThrow(() -> new QaServiceException(QaErrorCode.CONVERSATION_NOT_FOUND, "CONVERSATION_NOT_FOUND"));
        log.info("Conversation renamed: conversationId={} operation=rename outcome=renamed", conversationId);
        return summary(renamed);
    }

    @Override
    public void delete(AccountId account, UUID conversationId) {
        Boolean deleted = db.inTransaction(status -> {
            boolean gone = conversations.delete(account.value(), conversationId);
            if (gone) audit.conversationDeletedInTransaction(conversationId);
            return gone;
        });
        if (!Boolean.TRUE.equals(deleted)) {
            throw new QaServiceException(QaErrorCode.CONVERSATION_NOT_FOUND, "CONVERSATION_NOT_FOUND");
        }
        log.info("Conversation deleted: conversationId={} operation=delete outcome=deleted", conversationId);
    }

    @Override
    public int deleteAll(AccountId account) {
        int total = 0;
        while (true) {
            Integer deleted = db.inTransaction(status -> conversations.deleteSomeOf(account.value(), DELETE_BATCH));
            total += deleted;
            if (deleted < DELETE_BATCH) break;
        }
        db.inTransaction(status -> {
            usage.forgetAccount(account.value());
            audit.allConversationsDeletedInTransaction();
            return null;
        });
        log.info("Conversations deleted: count={} operation=delete_all outcome=deleted", total);
        return total;
    }

    /** The start of the first question, one line, no control or bidi characters (shown in lists). */
    static String title(String question) {
        String line = question.replaceAll("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]", " ").replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) line = "?";
        if (line.codePointCount(0, line.length()) <= TITLE_CHARS) return line;
        return line.substring(0, line.offsetByCodePoints(0, TITLE_CHARS - 1)).strip() + "…";
    }

    /**
     * PostgreSQL text cannot hold U+0000. A question (valid JSON with an escaped U+0000) or an answer carrying one made the whole
     * write fail: the turn was lost and the failure counted as an outage (HistoryNotStored), at any user's will. It is
     * kept visible as U+FFFD; the length (and so every CHECK) stays the same.
     */
    static String storable(String text) {
        return text.indexOf('\0') < 0 ? text : text.replace('\0', '�');
    }

    private static String bounded(String answer) {
        if (answer.length() <= MAX_ANSWER_CHARS) return answer;
        int end = MAX_ANSWER_CHARS;
        if (Character.isHighSurrogate(answer.charAt(end - 1))) end--;
        return answer.substring(0, end);
    }

    private static int millis(long value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private static ConversationSummary summary(ConversationRow row) {
        return new ConversationSummary(row.id(), row.title(), row.createdAt(), row.updatedAt(), row.turnCount());
    }
}
