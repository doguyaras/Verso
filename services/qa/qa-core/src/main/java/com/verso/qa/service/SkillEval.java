package com.verso.qa.service;

import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.qa.api.dto.SkillStaleReason;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.repository.SkillRepository.EvalCase;
import com.verso.qa.repository.SkillRepository.SourceRow;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The skills' part of the nightly golden-set check (ADR-0053; the run, its lock and its lease are KnowledgeEval's). For
 * every golden case of an APPROVED skill's current version, without any model call:
 *
 * <ul>
 *   <li>are the skill's bound hashes still there ({@link DocumentProvenance#sourceHashes}, no reader filter, no text)?
 *       If not: {@code SOURCE_LOST}, and the skill goes STALE (SOURCE_CHANGED) once, the safety net behind the
 *       document outbox;</li>
 *   <li>else the stored question vector is searched as the audience of the skill's source documents: is a chunk of the
 *       expected page in the top k? {@code FOUND} or {@code MISSED}; another embedding model is {@code SKIPPED}.</li>
 * </ul>
 *
 * The skill counts are stored beside the card counts; they do not enter the card recall, its baseline or its alert.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillEval {

    /** Cases between two lease renewals. */
    private static final int RENEW_EVERY = 50;

    /** The skill counts of one run. */
    public record Counts(int cases, int found, int missed, int sourceLost) {
    }

    private final QaDatabase db;
    private final SkillRepository skills;
    private final DocumentProvenance provenance;
    private final SkillTransitions transitions;

    public SkillEval(QaDatabase db, SkillRepository skills, DocumentProvenance provenance,
                     SkillTransitions transitions) {
        this.db = db;
        this.skills = skills;
        this.provenance = provenance;
        this.transitions = transitions;
    }

    /**
     * @param renew renews the run's lease; false means the lease is lost and the run stops (an exception)
     */
    public Counts evaluate(UUID runId, int topK, int maxCases, BooleanSupplier renew) {
        List<EvalCase> cases = skills.approvedCases(maxCases);
        Map<UUID, List<SourceRow>> sourcesOf = new HashMap<>();
        Map<UUID, List<SourceChunk>> currentOf = new HashMap<>();
        Map<UUID, Boolean> intactOf = new HashMap<>();
        int found = 0;
        int missed = 0;
        int lost = 0;
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0 && i % RENEW_EVERY == 0 && !renew.getAsBoolean()) throw new IllegalStateException("lease lost");
            EvalCase golden = cases.get(i);
            List<SourceRow> sources = sourcesOf.computeIfAbsent(golden.skillId(),
                    id -> skills.sources(id, golden.version()));
            List<Binding> bindings = SkillContent.bindings(sources);
            List<SourceChunk> current = currentOf.computeIfAbsent(golden.skillId(), id -> {
                Set<SourcePage> pages = SkillRules.pages(bindings);
                return pages.isEmpty() || pages.size() > DocumentProvenance.MAX_PAGES ? List.of()
                        : provenance.sourceHashes(pages);
            });
            boolean intact = intactOf.computeIfAbsent(golden.skillId(), id -> {
                boolean ok = SkillRules.intact(bindings, current);
                if (!ok) {
                    db.inTransaction(status -> transitions.markStaleInTransaction(id, SkillStaleReason.SOURCE_CHANGED));
                }
                return ok;
            });
            String outcome;
            Integer rank = null;
            if (!intact) {
                outcome = "SOURCE_LOST";
                lost++;
            } else {
                Optional<float[]> vector = skills.goldenEmbedding(golden.skillId(), golden.version(), golden.position());
                Set<UUID> documents = new LinkedHashSet<>(SkillContent.documents(sources));
                List<UUID> ranked = vector.isEmpty() ? List.of() : provenance.rankForAudience(documents,
                        golden.question(), new QuestionEmbedding(golden.embeddingModel(), vector.get()), topK);
                Set<UUID> wanted = current.stream().filter(c -> c.page().equals(golden.page()))
                        .map(SourceChunk::chunkId).collect(Collectors.toSet());
                rank = rankOf(ranked, wanted);
                if (ranked.isEmpty()) {
                    outcome = "SKIPPED";
                } else if (rank != null) {
                    outcome = "FOUND";
                    found++;
                } else {
                    outcome = "MISSED";
                    missed++;
                }
            }
            skills.recordEval(runId, golden.skillId(), golden.version(), golden.position(), outcome, rank);
        }
        return new Counts(cases.size(), found, missed, lost);
    }

    /** The 1-based rank of the first wanted chunk, or null. */
    static Integer rankOf(List<UUID> ranked, Set<UUID> wanted) {
        for (int position = 0; position < ranked.size(); position++) {
            if (wanted.contains(ranked.get(position))) return position + 1;
        }
        return null;
    }
}
