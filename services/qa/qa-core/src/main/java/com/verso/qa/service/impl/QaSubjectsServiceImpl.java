package com.verso.qa.service.impl;

import com.verso.platform.security.authority.VersoRoles;
import com.verso.qa.api.QaSubjects;
import com.verso.qa.api.dto.SubjectConversation;
import com.verso.qa.api.dto.SubjectHistoryCounts;
import com.verso.qa.api.dto.SubjectTurn;
import com.verso.qa.api.dto.SubjectTurn.SubjectCitation;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.SubjectHistoryRepository;
import com.verso.qa.repository.SubjectHistoryRepository.ConversationHead;
import com.verso.qa.repository.SubjectHistoryRepository.TurnRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * {@link QaSubjects} (ADR-0034). Every write is a short transaction of the qa schema only (operation-consistency 1):
 * the erasure deletes a few conversations per transaction, so 200-turn conversations never meet the role's 10 s
 * statement timeout (ADR-0023 section 4), and repeats until none is left. An export batch is read in one read-only
 * transaction (READ COMMITTED: a turn written meanwhile may or may not appear; nothing is torn, only newer).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaSubjectsServiceImpl implements QaSubjects {

    /** Conversations per erasure transaction: at most 4 000 turns with their citations and ratings. */
    static final int DELETE_BATCH = 20;
    /** Answers per redaction transaction. */
    static final int REDACT_BATCH = 500;

    private final QaDatabase db;
    private final SubjectHistoryRepository history;
    private final KnowledgeCardRepository knowledge;

    public QaSubjectsServiceImpl(QaDatabase db, SubjectHistoryRepository history, KnowledgeCardRepository knowledge) {
        this.knowledge = knowledge;
        this.db = db;
        this.history = history;
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public SubjectHistoryCounts count(String sub) {
        return history.count(sub);
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public List<SubjectConversation> conversations(String sub, UUID after, int limit) {
        if (limit < 1 || limit > MAX_CONVERSATION_BATCH) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_CONVERSATION_BATCH);
        }
        return db.inTransaction(status -> {
            status.setRollbackOnly();
            List<ConversationHead> heads = history.conversations(sub, after, limit);
            if (heads.isEmpty()) return List.of();
            List<UUID> ids = heads.stream().map(ConversationHead::id).toList();
            Map<UUID, List<SubjectCitation>> cited = history.citations(sub, ids).stream().collect(Collectors.groupingBy(
                    SubjectHistoryRepository.CitationRow::turnId, LinkedHashMap::new,
                    Collectors.mapping(SubjectHistoryRepository.CitationRow::citation, Collectors.toList())));
            Map<UUID, List<SubjectTurn>> turns = new LinkedHashMap<>();
            for (TurnRow row : history.turns(sub, ids)) {
                turns.computeIfAbsent(row.conversationId(), k -> new ArrayList<>()).add(new SubjectTurn(row.id(),
                        row.createdAt(), row.question(), row.answer(), row.outcome(), row.mode(), row.model(),
                        cited.getOrDefault(row.id(), List.of()), row.feedback()));
            }
            return heads.stream().map(h -> new SubjectConversation(h.id(), h.title(), h.createdAt(), h.updatedAt(),
                    turns.getOrDefault(h.id(), List.of()))).toList();
        });
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public SubjectHistoryCounts deleteHistory(String sub) {
        SubjectHistoryCounts total = SubjectHistoryCounts.NONE;
        while (true) {
            SubjectHistoryCounts batch = db.inTransaction(status -> {
                history.lockSubject(sub);
                return history.deleteConversations(sub, DELETE_BATCH);
            });
            total = total.plus(batch);
            if (batch.conversations() == 0) break;
        }
        int days = db.inTransaction(status -> {
            history.lockSubject(sub);
            // ADR-0049: the subject's pseudonymous disputes of verified answers go too (the counts on the cards stay).
            knowledge.deleteDisputesOf(sub);
            return history.deleteActiveDays(sub);
        });
        return total.plus(new SubjectHistoryCounts(0, 0, 0, days));
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public int redactAnswersCiting(Collection<UUID> documentIds) {
        LinkedHashSet<UUID> ids = documentIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.size() > MAX_REDACTION_IDS) throw new IllegalArgumentException("at most " + MAX_REDACTION_IDS + " ids");
        if (ids.isEmpty()) return 0;
        int total = 0;
        while (true) {
            Integer batch = db.inTransaction(status -> history.redactAnswersCiting(ids, REDACT_BATCH));
            total += batch;
            if (batch < REDACT_BATCH) return total;
        }
    }
}
