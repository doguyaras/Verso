package com.verso.qa.service;

import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.qa.api.dto.ProcedureMatch;
import com.verso.qa.config.QaSkillProperties;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.repository.SkillRepository.Candidate;
import com.verso.qa.repository.SkillRepository.SkillRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The serving side of skills in the ask flow (ADR-0053), behind {@code verso.qa.skills.serving-enabled} (off by
 * default). With the question's own vector (no second embedding):
 *
 * <ol>
 *   <li>the APPROVED skills of the current embedding model whose nearest trigger is closest (exact cosine, qa schema);</li>
 *   <li>only those at or above {@code verso.qa.skills.match-threshold} (conservative);</li>
 *   <li>for each, in order: every bound chunk is read again <b>with the caller's principals</b> (ADR-0028) and must
 *       still carry its hash; a caller who cannot read every source gets nothing from that skill (no existence
 *       oracle).</li>
 * </ol>
 *
 * The match is shown <b>above</b> the generated answer, never instead of it (ADR-0053 "serving decision"). A database
 * failure of the lookup only drops the box (logged with the exception type). Logs carry ids and counts, never a
 * question or a step (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillMatcher.class);

    private final SkillRepository skills;
    private final SkillContent content;
    private final QaSkillProperties properties;
    private final MeterRegistry registry;
    private final Map<String, Counter> lookups = new ConcurrentHashMap<>();

    public SkillMatcher(SkillRepository skills, SkillContent content, QaSkillProperties properties,
                        MeterRegistry registry) {
        this.skills = skills;
        this.content = content;
        this.properties = properties;
        this.registry = registry;
        for (String outcome : List.of("matched", "no_match", "not_readable", "failed")) counter(outcome);
    }

    public boolean enabled() {
        return properties.servingEnabled();
    }

    /** The candidates at or above the threshold, most similar first (the lookup is already ordered). */
    static List<Candidate> matching(List<Candidate> candidates, double threshold) {
        return candidates.stream().filter(c -> Double.isFinite(c.similarity()) && c.similarity() >= threshold).toList();
    }

    public Optional<ProcedureMatch> match(Reader reader, QuestionEmbedding embedding) {
        if (!enabled()) return Optional.empty();
        try {
            List<Candidate> matching = matching(skills.nearestApproved(embedding.vector(), embedding.model(),
                    properties.matchCandidates()), properties.matchThreshold());
            if (matching.isEmpty()) {
                counter("no_match").increment();
                return Optional.empty();
            }
            for (Candidate candidate : matching) {
                Optional<SkillRow> skill = skills.find(candidate.skillId());
                // Read again after the lookup: a skill that went stale or was archived meanwhile is never shown.
                if (skill.isEmpty() || !SkillRules.servable(skill.get().state())) continue;
                Optional<SkillContent.Loaded> loaded = content.load(skill.get());
                if (loaded.isEmpty()) continue;
                SkillContent.View view = content.view(reader, loaded.get().sources());
                if (!view.intact()) continue;
                counter("matched").increment();
                log.info("Procedure matched: skillId={} version={} steps={}", skill.get().id(), skill.get().version(),
                        loaded.get().steps().size());
                return Optional.of(SkillContent.procedure(loaded.get(), view));
            }
            counter("not_readable").increment();
            return Optional.empty();
        } catch (DataAccessException e) {
            counter("failed").increment();
            log.warn("Procedure lookup failed: outcome=skipped exceptionType={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Counter counter(String outcome) {
        return lookups.computeIfAbsent(outcome, o -> Counter.builder("verso.qa.skills.lookups").tag("outcome", o)
                .description("Procedure lookups of the ask flow by outcome (ADR-0053)").register(registry));
    }
}
