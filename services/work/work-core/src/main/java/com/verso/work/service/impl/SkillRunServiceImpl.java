package com.verso.work.service.impl;

import com.verso.audit.api.enums.AuditAction;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Passage;
import com.verso.document.api.dto.Reader;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.dto.SkillRunEvent;
import com.verso.work.api.dto.SkillRunPage;
import com.verso.work.api.dto.SkillRunResponse;
import com.verso.work.api.dto.SkillRunSummary;
import com.verso.work.api.enums.SkillCitationKind;
import com.verso.work.api.enums.SkillExportFormat;
import com.verso.work.api.enums.SkillRunStage;
import com.verso.work.api.enums.SkillRunStatus;
import com.verso.work.config.WorkProperties;
import com.verso.work.domain.Anchors;
import com.verso.work.domain.InputRules;
import com.verso.work.domain.MarkdownExport;
import com.verso.work.domain.OutputSchema;
import com.verso.work.domain.Source;
import com.verso.work.exception.WorkErrorCode;
import com.verso.work.exception.WorkServiceException;
import com.verso.work.api.SkillModel;
import com.verso.work.outbox.WorkAudit;
import com.verso.work.repository.SkillRunRepository;
import com.verso.work.repository.SkillRunRepository.RunRow;
import com.verso.work.repository.SkillRunRepository.SourceRow;
import com.verso.work.repository.WorkDatabase;
import com.verso.work.api.dto.SkillSummary;
import com.verso.work.service.SkillCatalog;
import com.verso.work.service.SkillRunService;
import com.verso.work.service.SkillRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The run endpoints of ADR-0057 for the caller's own runs: start, read, history, edit, approve, rate, export, delete.
 * Ownership is the validated token's sub in every statement; another user's run is a 404 (its existence is not
 * revealed). Every change is audited in its own transaction with ids and fixed words only.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillRunServiceImpl implements SkillRunService {

    private final SkillCatalog catalog;
    private final SkillModel model;
    private final SkillRunner runner;
    private final SkillRunRepository runs;
    private final WorkDatabase db;
    private final WorkAudit audit;
    private final DocumentRetrieval retrieval;
    private final WorkProperties properties;
    private final JsonMapper json;

    public SkillRunServiceImpl(SkillCatalog catalog, SkillModel model, SkillRunner runner, SkillRunRepository runs,
                               WorkDatabase db, WorkAudit audit, DocumentRetrieval retrieval, WorkProperties properties,
                               JsonMapper json) {
        this.catalog = catalog;
        this.model = model;
        this.runner = runner;
        this.runs = runs;
        this.db = db;
        this.audit = audit;
        this.retrieval = retrieval;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public List<SkillSummary> gallery() {
        return catalog.enabled().stream().map(e -> SkillCatalog.summary(e.definition(), e.origin())).toList();
    }

    @Override
    public SkillRunResponse start(Reader reader, String skillId, Map<String, Object> inputs) {
        SkillCatalog.Entry entry = catalog.require(skillId);
        SkillDefinition skill = entry.definition();
        InputRules.Result checked = InputRules.check(skill, inputs);
        if (!checked.valid()) {
            throw new WorkServiceException(WorkErrorCode.INPUT_INVALID, "FORM", checked.errors());
        }
        SkillModel.Lease lease = model.tryAcquire(properties.modelWait())
                .orElseThrow(() -> new WorkServiceException(WorkErrorCode.MODEL_BUSY, "NO_MODEL_SLOT"));
        UUID id = UUID.randomUUID();
        boolean inserted;
        try {
            String definition = json.writeValueAsString(skill);
            String values = json.writeValueAsString(checked.values());
            inserted = db.transactionally(() -> {
                if (!runs.insertRunning(id, reader.accountId(), skill.id(), skill.version(), entry.customSkillId(),
                        definition, skill.steps().size(), values, properties.lease())) {
                    return false;
                }
                audit.run(AuditAction.TASK_SKILL_RUN_STARTED, id, entry.origin().name());
                return true;
            });
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
        if (!inserted) {
            lease.close();
            throw new WorkServiceException(WorkErrorCode.RUN_ACTIVE, "RUN_ACTIVE");
        }
        runner.submit(new SkillRunner.Job(id, skill, checked.values(), reader, lease));
        return get(reader, id);
    }

    public SkillRunResponse get(Reader reader, UUID runId) {
        return response(row(reader, runId), reader);
    }

    public SkillRunPage page(String owner, int page, int size) {
        if (page < 0 || page > 10_000 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new WorkServiceException(WorkErrorCode.PAGE_INVALID, "PAGE");
        }
        List<SkillRunSummary> items = runs.page(owner, page, size).stream().map(this::summary).toList();
        return new SkillRunPage(items, page, size, runs.count(owner));
    }

    /** The stage of the caller's run for the progress stream; empty when it is gone. */
    public Optional<SkillRunEvent> event(String owner, UUID runId) {
        return runs.find(runId, owner).map(r -> {
            SkillRunStatus status = SkillRunStatus.valueOf(r.status());
            String title = null;
            if (r.step() != null && SkillRunStage.GENERATING.name().equals(r.stage())) {
                List<SkillDefinition.Step> steps = definition(r).steps();
                if (r.step() <= steps.size()) title = steps.get(r.step() - 1).title();
            }
            return new SkillRunEvent(SkillRunStage.valueOf(r.stage()), r.step(), r.stepCount(), title,
                    status == SkillRunStatus.RUNNING ? null : status, r.failureCode());
        });
    }

    /** The owner's edit: checked against the run's own schema and sources, re-grounded, a draft again. */
    public SkillRunResponse edit(Reader reader, UUID runId, SkillOutput output) {
        RunRow r = finished(reader, runId);
        SkillDefinition skill = definition(r);
        List<Source> sources = sourcesWithText(r, reader);
        OutputSchema.Checked checked = OutputSchema.check(skill, output, sources.size());
        if (!checked.valid()) throw new WorkServiceException(WorkErrorCode.OUTPUT_INVALID, "EDIT", checked.errors());
        Anchors.Result grounded = Anchors.check(checked.output(), sources, Anchors.inputsText(inputs(r)));
        SkillRunResponse.Checks previous = checks(r);
        int unreadable = (int) sources.stream().filter(s -> s.kind() == SkillCitationKind.PASSAGE && s.text() == null).count();
        SkillRunResponse.Checks checks = SkillRunner.checks(skill, grounded, unreadable,
                previous != null && previous.repaired());
        String outputJson = json.writeValueAsString(grounded.output());
        String checksJson = json.writeValueAsString(checks);
        db.transactionally(() -> {
            if (!runs.replaceOutput(runId, reader.accountId(), outputJson, checksJson)) {
                throw new WorkServiceException(WorkErrorCode.RUN_NOT_FINISHED, "NOT_FINISHED");
            }
            audit.run(AuditAction.TASK_SKILL_RUN_EDITED, runId, null);
            return null;
        });
        return get(reader, runId);
    }

    public SkillRunResponse approve(Reader reader, UUID runId) {
        finished(reader, runId);
        db.transactionally(() -> {
            if (!runs.approve(runId, reader.accountId())) {
                throw new WorkServiceException(WorkErrorCode.RUN_NOT_FINISHED, "NOT_FINISHED");
            }
            audit.run(AuditAction.TASK_SKILL_RUN_APPROVED, runId, null);
            return null;
        });
        return get(reader, runId);
    }

    public void rate(Reader reader, UUID runId, int rating) {
        finished(reader, runId);
        db.transactionally(() -> {
            if (!runs.rate(runId, reader.accountId(), rating)) {
                throw new WorkServiceException(WorkErrorCode.RUN_NOT_FINISHED, "NOT_FINISHED");
            }
            audit.run(AuditAction.TASK_SKILL_RUN_RATED, runId, "RATING_" + rating);
            return null;
        });
    }

    public Export export(Reader reader, UUID runId, String format) {
        SkillExportFormat f;
        try {
            f = SkillExportFormat.valueOf(format == null ? "" : format.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WorkServiceException(WorkErrorCode.EXPORT_FORMAT_INVALID, "FORMAT");
        }
        RunRow r = finished(reader, runId);
        SkillDefinition skill = definition(r);
        SkillRunResponse response = response(r, reader);
        String name = skill.id() + "-" + runId.toString().substring(0, 8);
        String body;
        Export export;
        if (f == SkillExportFormat.MARKDOWN) {
            Map<String, String> labels = skill.inputs().stream()
                    .collect(Collectors.toMap(SkillDefinition.Input::key, SkillDefinition.Input::label));
            body = MarkdownExport.render(skill, response, labels);
            export = new Export(body, "text/markdown;charset=UTF-8", name + ".md");
        } else {
            // The draft, its checks and where each number points; not the form inputs and not the passage texts.
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("skillId", skill.id());
            file.put("skillVersion", r.skillVersion());
            file.put("skillName", skill.name());
            file.put("status", response.status());
            file.put("disclaimer", skill.disclaimer());
            file.put("framework", skill.framework());
            file.put("createdAt", response.createdAt());
            file.put("output", response.output());
            file.put("checks", response.checks());
            file.put("citations", response.citations().stream().map(c -> new SkillRunResponse.Citation(c.number(),
                    c.kind(), c.passageId(), c.documentId(), c.fileName(), c.page(), c.unit(), c.collectionId(),
                    c.collectionName(), c.inputKey(), null, c.accessible())).toList());
            body = json.writeValueAsString(file);
            export = new Export(body, "application/json", name + ".json");
        }
        db.transactionally(() -> {
            audit.run(AuditAction.TASK_SKILL_RUN_EXPORTED, runId, f.name());
            return null;
        });
        return export;
    }

    public void delete(Reader reader, UUID runId) {
        db.transactionally(() -> {
            if (!runs.delete(runId, reader.accountId())) {
                throw new WorkServiceException(WorkErrorCode.RUN_NOT_FOUND, "NOT_FOUND");
            }
            audit.run(AuditAction.TASK_SKILL_RUN_DELETED, runId, null);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ mapping

    private RunRow row(Reader reader, UUID runId) {
        return runs.find(runId, reader.accountId())
                .orElseThrow(() -> new WorkServiceException(WorkErrorCode.RUN_NOT_FOUND, "NOT_FOUND"));
    }

    private RunRow finished(Reader reader, UUID runId) {
        RunRow r = row(reader, runId);
        if (!"DRAFT".equals(r.status()) && !"APPROVED".equals(r.status())) {
            throw new WorkServiceException(WorkErrorCode.RUN_NOT_FINISHED, r.status());
        }
        return r;
    }

    private SkillRunResponse response(RunRow r, Reader reader) {
        SkillDefinition skill = definition(r);
        Map<String, Object> values = inputs(r);
        List<SkillRunResponse.Citation> citations = new ArrayList<>();
        for (SourceRow s : runs.sources(r.id())) {
            if ("PASSAGE".equals(s.kind())) {
                Optional<Passage> p = retrieval.passage(reader, s.passageId());
                citations.add(p.map(passage -> new SkillRunResponse.Citation(s.number(), SkillCitationKind.PASSAGE,
                                s.passageId(), s.documentId(), passage.fileName(), passage.pageNumber(),
                                passage.unit() == null ? s.unit() : passage.unit().name(), passage.collectionId(),
                                passage.collectionName(), null, passage.content(), true))
                        .orElseGet(() -> new SkillRunResponse.Citation(s.number(), SkillCitationKind.PASSAGE,
                                s.passageId(), s.documentId(), null, s.page(), s.unit(), null, null, null, null, false)));
            } else {
                citations.add(new SkillRunResponse.Citation(s.number(), SkillCitationKind.INPUT, null, null, null,
                        null, null, null, null, s.inputKey(), excerpt(values, s), true));
            }
        }
        SkillOutput output = r.outputJson() == null ? null : json.readValue(r.outputJson(), SkillOutput.class);
        return new SkillRunResponse(r.id(), r.skillId(), r.skillVersion(), skill.name(), SkillRunStatus.valueOf(r.status()),
                SkillRunStage.valueOf(r.stage()), r.step(), r.stepCount(), r.failureCode(), values, output,
                List.copyOf(citations), checks(r), r.edited(), r.rating(), skill.disclaimer(), r.modelId(),
                r.createdAt(), r.finishedAt(), r.approvedAt());
    }

    /** The run's sources with their text as the caller can read them now (an unreadable passage has none). */
    private List<Source> sourcesWithText(RunRow r, Reader reader) {
        Map<String, Object> values = inputs(r);
        List<Source> sources = new ArrayList<>();
        for (SourceRow s : runs.sources(r.id())) {
            if ("PASSAGE".equals(s.kind())) {
                String text = retrieval.passage(reader, s.passageId()).map(Passage::content).orElse(null);
                sources.add(new Source(s.number(), SkillCitationKind.PASSAGE, s.passageId(), s.documentId(), null,
                        s.page(), s.unit(), s.collectionId(), null, null, null, null, text == null ? "" : text));
            } else {
                sources.add(Source.input(s.number(), s.inputKey(), s.start(), s.end(), excerpt(values, s)));
            }
        }
        return sources;
    }

    private static String excerpt(Map<String, Object> values, SourceRow s) {
        String text = InputRules.text(values, s.inputKey());
        int start = Math.max(0, Math.min(s.start(), text.length()));
        int end = Math.max(start, Math.min(s.end(), text.length()));
        return text.substring(start, end).strip();
    }

    private SkillRunSummary summary(RunRow r) {
        return new SkillRunSummary(r.id(), r.skillId(), definition(r).name(), SkillRunStatus.valueOf(r.status()),
                r.failureCode(), r.edited(), r.rating(), r.createdAt(), r.finishedAt());
    }

    private SkillDefinition definition(RunRow r) {
        return json.readValue(r.definitionJson(), SkillDefinition.class);
    }

    private Map<String, Object> inputs(RunRow r) {
        return json.readValue(r.inputsJson(), new TypeReference<Map<String, Object>>() { });
    }

    private SkillRunResponse.Checks checks(RunRow r) {
        return r.checksJson() == null ? null : json.readValue(r.checksJson(), SkillRunResponse.Checks.class);
    }
}
