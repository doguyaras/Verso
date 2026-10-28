package com.verso.work.service;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.dto.SkillRunResponse;
import com.verso.work.api.enums.SkillCitationKind;
import com.verso.work.api.enums.SkillRunStage;
import com.verso.work.config.WorkProperties;
import com.verso.work.domain.Anchors;
import com.verso.work.domain.InputParts;
import com.verso.work.domain.InputRules;
import com.verso.work.domain.OutputSchema;
import com.verso.work.domain.PromptComposer;
import com.verso.work.domain.Source;
import com.verso.work.model.SkillModel;
import com.verso.work.model.SkillModelException;
import com.verso.work.outbox.WorkAudit;
import com.verso.work.repository.SkillRunRepository;
import com.verso.work.repository.SkillRunRepository.SourceRow;
import com.verso.work.repository.WorkDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one accepted skill run in the background (ADR-0057), on the instance that accepted it, holding the model slot
 * the request acquired. Stages: retrieval with the caller's access (document-api), the step chain through
 * {@link SkillModel}, the output contract with one repair, the deterministic checks, then the DRAFT is stored with its
 * sources in one transaction with the audit record. Every stage renews the run's lease; a run that is deleted or swept
 * meanwhile simply stops. Logs carry the run id, counts and fixed words: never an input, a passage or an output
 * (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillRunner.class);
    private static final ExecutorService RUNS = Executors.newVirtualThreadPerTaskExecutor();

    /** What a run needs; the reader is the validated token's principals captured by the request. */
    public record Job(UUID runId, SkillDefinition skill, Map<String, Object> values, Reader reader,
                      SkillModel.Lease lease) {

        @Override
        public String toString() {
            return "Job[runId=" + runId + "]";
        }
    }

    private final DocumentRetrieval retrieval;
    private final SkillModel model;
    private final SkillRunRepository runs;
    private final WorkDatabase db;
    private final WorkAudit audit;
    private final WorkProperties properties;
    private final JsonMapper json;
    private final MeterRegistry registry;

    public SkillRunner(DocumentRetrieval retrieval, SkillModel model, SkillRunRepository runs, WorkDatabase db,
                       WorkAudit audit, WorkProperties properties, JsonMapper json, MeterRegistry registry) {
        this.retrieval = retrieval;
        this.model = model;
        this.runs = runs;
        this.db = db;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
        this.registry = registry;
    }

    /** Starts the run on a virtual thread; the lease is released when it ends, whatever happens. */
    public void submit(Job job) {
        try {
            RUNS.submit(() -> execute(job));
        } catch (RuntimeException e) {
            job.lease().close();
            throw e;
        }
    }

    void execute(Job job) {
        UUID id = job.runId();
        SkillDefinition skill = job.skill();
        String modelId = model.modelId();
        long started = System.nanoTime();
        try (SkillModel.Lease ignored = job.lease()) {
            if (!runs.stage(id, SkillRunStage.RETRIEVING.name(), null, properties.lease())) return;
            List<Source> sources = new ArrayList<>();
            try {
                sources.addAll(passages(skill, job.values(), job.reader()));
            } catch (RuntimeException e) {
                log.warn("Skill run failed: runId={} reason=RETRIEVAL_FAILED exceptionType={}", id,
                        e.getClass().getSimpleName());
                fail(id, "RETRIEVAL_FAILED", modelId);
                return;
            }
            sources.addAll(InputParts.split(skill, job.values(), sources.size() + 1));
            List<PromptComposer.Notes> notes = new ArrayList<>();
            PromptComposer.Prompt last = null;
            String answer = null;
            for (int i = 0; i < skill.steps().size(); i++) {
                SkillDefinition.Step step = skill.steps().get(i);
                if (!runs.stage(id, SkillRunStage.GENERATING.name(), i + 1, properties.lease())) return;
                last = PromptComposer.step(skill, i, job.values(), sources, notes);
                answer = model.complete(new SkillModel.Call("skill:" + skill.id() + ":" + step.key(), last.system(),
                        last.user(), properties.callTimeout()));
                if (i < skill.steps().size() - 1) {
                    notes.add(new PromptComposer.Notes(step.title(), cap(answer, step.maxChars())));
                }
            }
            OutputSchema.Checked checked = OutputSchema.parse(skill, answer, sources.size(), this::readJson);
            boolean repaired = false;
            if (!checked.valid()) {
                log.info("Skill run output repair: runId={} errors={}", id, checked.errors().size());
                if (!runs.stage(id, SkillRunStage.REPAIRING.name(), null, properties.lease())) return;
                PromptComposer.Prompt repair = PromptComposer.repair(last, answer, checked.errors());
                String second = model.complete(new SkillModel.Call("skill:" + skill.id() + ":repair", repair.system(),
                        repair.user(), properties.callTimeout()));
                checked = OutputSchema.parse(skill, second, sources.size(), this::readJson);
                repaired = true;
                if (!checked.valid()) {
                    log.warn("Skill run failed: runId={} reason=OUTPUT_INVALID errors={}", id, checked.errors().size());
                    fail(id, "OUTPUT_INVALID", modelId);
                    return;
                }
            }
            if (!runs.stage(id, SkillRunStage.CHECKING.name(), null, properties.lease())) return;
            Anchors.Result grounded = Anchors.check(checked.output(), sources, Anchors.inputsText(job.values()));
            SkillRunResponse.Checks checks = checks(skill, grounded, unreadable(grounded.output(), sources, job.reader()),
                    repaired);
            String output = json.writeValueAsString(grounded.output());
            String checksJson = json.writeValueAsString(checks);
            List<SourceRow> rows = sources.stream().map(SkillRunner::row).toList();
            List<UUID> documents = sources.stream().filter(s -> s.documentId() != null).map(Source::documentId)
                    .distinct().toList();
            boolean stored = db.transactionally(() -> {
                if (!runs.finish(id, output, checksJson, modelId, rows)) return false;
                audit.finished(id, "DRAFT", modelId, documents);
                return true;
            });
            if (stored) {
                registry.counter("verso.work.runs", "outcome", "draft").increment();
                log.info("Skill run finished: runId={} sources={} rows={} notAssessable={} unverified={} repaired={} "
                                + "durationMs={}", id, sources.size(), checks.rows(), checks.notAssessable(),
                        checks.unverified(), repaired, (System.nanoTime() - started) / 1_000_000);
            }
        } catch (SkillModelException e) {
            log.warn("Skill run failed: runId={} reason={}", id, e.reason());
            fail(id, e.reason(), modelId);
        } catch (RuntimeException e) {
            log.error("Skill run failed: runId={} reason=RUN_FAILED exceptionType={}", id, e.getClass().getSimpleName());
            fail(id, "RUN_FAILED", modelId);
        }
    }

    private void fail(UUID id, String code, String modelId) {
        registry.counter("verso.work.runs", "outcome", "failed").increment();
        try {
            db.transactionally(() -> {
                if (runs.fail(id, code, modelId)) audit.finished(id, code, modelId, List.of());
                return null;
            });
        } catch (RuntimeException e) {
            // The sweep fails it as INTERRUPTED once its lease passes.
            log.error("Skill run failure not stored: runId={} exceptionType={}", id, e.getClass().getSimpleName());
        }
    }

    /**
     * The grounding passages: each query searched with the caller's access, merged round-robin, deduplicated by
     * passage, limited to the selected documents, the selected collections and (custom skills) the skill's scope.
     */
    List<Source> passages(SkillDefinition skill, Map<String, Object> values, Reader reader) {
        if (skill.grounding() == null) return List.of();
        SkillDefinition.Grounding g = skill.grounding();
        Set<UUID> documents = new HashSet<>(InputRules.ids(values, g.documentsInput()));
        Set<UUID> collections = new HashSet<>(InputRules.ids(values, g.collectionsInput()));
        Set<UUID> scope = g.scopeCollections() == null ? Set.of() : new HashSet<>(g.scopeCollections());
        boolean filtered = !documents.isEmpty() || !collections.isEmpty() || !scope.isEmpty();
        int topK = filtered ? properties.searchTopK() : Math.min(properties.searchTopK(), g.maxPassages() * 2);
        List<Iterator<RetrievedPassage>> lists = new ArrayList<>();
        for (String query : PromptComposer.queries(skill, values)) {
            lists.add(retrieval.search(reader, query, topK).iterator());
        }
        Map<String, RetrievedPassage> picked = new LinkedHashMap<>();
        boolean more = true;
        while (more && picked.size() < g.maxPassages()) {
            more = false;
            for (Iterator<RetrievedPassage> it : lists) {
                while (it.hasNext()) {
                    RetrievedPassage p = it.next();
                    more = true;
                    if (!documents.isEmpty() && !documents.contains(p.documentId())) continue;
                    if (!collections.isEmpty() && (p.collectionId() == null || !collections.contains(p.collectionId()))) continue;
                    if (!scope.isEmpty() && (p.collectionId() == null || !scope.contains(p.collectionId()))) continue;
                    String key = p.passageId() != null ? p.passageId().toString() : p.documentId() + ":" + p.pageNumber();
                    if (picked.putIfAbsent(key, p) == null) break;
                }
                if (picked.size() >= g.maxPassages()) break;
            }
        }
        List<Source> sources = new ArrayList<>();
        for (RetrievedPassage p : picked.values()) {
            if (p.passageId() == null) continue;
            sources.add(new Source(sources.size() + 1, SkillCitationKind.PASSAGE, p.passageId(), p.documentId(),
                    p.fileName(), p.pageNumber(), p.unit() == null ? null : p.unit().name(), p.collectionId(),
                    p.collectionName(), null, null, null, p.content()));
        }
        return sources;
    }

    /** Cited passages that the caller can no longer read at the end of the run (deleted or access lost meanwhile). */
    private int unreadable(SkillOutput output, List<Source> sources, Reader reader) {
        Set<Integer> cited = new HashSet<>();
        for (SkillOutput.Section s : output.sections()) {
            if (s.evidence() != null) cited.addAll(s.evidence());
            if (s.rows() != null) s.rows().forEach(r -> cited.addAll(r.evidence()));
        }
        int missing = 0;
        for (Integer n : cited) {
            Source s = sources.get(n - 1);
            if (s.kind() == SkillCitationKind.PASSAGE && retrieval.passage(reader, s.passageId()).isEmpty()) missing++;
        }
        return missing;
    }

    public static SkillRunResponse.Checks checks(SkillDefinition skill, Anchors.Result grounded, int unreadable,
                                          boolean repaired) {
        int rows = 0;
        int notAssessable = 0;
        for (SkillOutput.Section s : grounded.output().sections()) {
            if (s.rows() != null) {
                rows += s.rows().size();
                notAssessable += (int) s.rows().stream().filter(r -> Boolean.TRUE.equals(r.notAssessable())).count();
            } else {
                rows++;
                if (Boolean.TRUE.equals(s.notAssessable())) notAssessable++;
            }
        }
        boolean withinLength = OutputSchema.text(grounded.output()).length() <= skill.maxOutputChars() + rows;
        return new SkillRunResponse.Checks(repaired, rows, notAssessable, grounded.unverifiedRows(), unreadable,
                withinLength, skill.disclaimer() != null && !skill.disclaimer().isBlank());
    }

    private static SourceRow row(Source s) {
        return new SourceRow(s.number(), s.kind().name(), s.passageId(), s.documentId(), s.page(), s.unit(),
                s.collectionId(), s.inputKey(), s.start(), s.end());
    }

    private Object readJson(String text) {
        return json.readValue(text, Object.class);
    }

    private static String cap(String text, Integer max) {
        if (text == null) return "";
        return max == null || text.length() <= max ? text : text.substring(0, max);
    }
}
