package com.verso.qa.service.impl;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.QaSubjects;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.SkillDraftRequest;
import com.verso.qa.api.dto.SkillOrigin;
import com.verso.qa.api.dto.SkillResponse;
import com.verso.qa.api.dto.SkillState;
import com.verso.qa.api.dto.SkillSuggestion;
import com.verso.qa.api.dto.SkillSummary;
import com.verso.qa.api.dto.SkillUpdateRequest;
import com.verso.qa.api.dto.SkillVersionDiff;
import com.verso.qa.config.QaKnowledgeProperties;
import com.verso.qa.config.QaSkillProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.repository.KnowledgeCardRepository.CardRow;
import com.verso.qa.repository.KnowledgeCardRepository.CitationRow;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository.ClusterRow;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.repository.SkillRepository.GoldenRow;
import com.verso.qa.repository.SkillRepository.SkillRow;
import com.verso.qa.repository.SkillRepository.SourceRow;
import com.verso.qa.repository.SkillRepository.StepRow;
import com.verso.qa.repository.SkillRepository.VersionRow;
import com.verso.qa.repository.TurnRepository;
import com.verso.qa.repository.TurnRepository.OwnedAnswer;
import com.verso.qa.service.KnowledgeCardRules;
import com.verso.qa.service.QaAudit;
import com.verso.qa.service.SkillCondenser;
import com.verso.qa.service.SkillContent;
import com.verso.qa.service.SkillContent.Loaded;
import com.verso.qa.service.SkillContent.View;
import com.verso.qa.service.SkillDiff;
import com.verso.qa.service.SkillMarkdown;
import com.verso.qa.service.SkillRules;
import com.verso.qa.service.SkillService;
import com.verso.qa.service.SkillTemplates;
import com.verso.qa.service.SkillTemplates.DraftStep;
import com.verso.qa.service.SkillTemplates.GoldenSeed;
import com.verso.qa.service.SkillTemplates.SourceAnswer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Skills (procedures) of the learning layer (ADR-0053). Every check that reads documents runs with the admin's own
 * principals (ADR-0028): an admin drafts, edits, approves and exports only what they can read now. Remote work (the
 * embeddings of triggers and golden questions, the optional local condensing call) and document reads happen before
 * the qa transaction, which then writes the version, its steps, bindings, triggers, golden cases, event and audit row
 * together (ADR-0039). Logs carry ids and counts, never a step, a question or a passage (llm-rules 2.1).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillServiceImpl implements SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillServiceImpl.class);
    /** Cards drafted from one cluster at most (each becomes at least one step). */
    private static final int CLUSTER_CARDS = 12;

    /** A step of a new version: its text, whether it is grounded, and its bound chunks. */
    private record NewStep(String instruction, boolean grounded, List<SourceRow> sources) {
    }

    /** A golden case of a new version. */
    private record NewGolden(String question, SourcePage page, String fact) {
    }

    private final QaDatabase db;
    private final SkillRepository skills;
    private final KnowledgeCardRepository cards;
    private final KnowledgeDiagnosticsRepository diagnostics;
    private final TurnRepository turns;
    private final DocumentProvenance provenance;
    private final DocumentRetrieval retrieval;
    private final DocumentCatalog catalog;
    private final CallerGroups groups;
    private final SkillContent content;
    private final SkillCondenser condenser;
    private final QaAudit audit;
    private final QaSkillProperties properties;
    private final QaKnowledgeProperties knowledge;
    private final Clock clock;

    public SkillServiceImpl(QaDatabase db, SkillRepository skills, KnowledgeCardRepository cards,
                            KnowledgeDiagnosticsRepository diagnostics, TurnRepository turns,
                            DocumentProvenance provenance, DocumentRetrieval retrieval, DocumentCatalog catalog,
                            CallerGroups groups, SkillContent content, SkillCondenser condenser, QaAudit audit,
                            QaSkillProperties properties, QaKnowledgeProperties knowledge, Clock clock) {
        this.db = db;
        this.skills = skills;
        this.cards = cards;
        this.diagnostics = diagnostics;
        this.turns = turns;
        this.provenance = provenance;
        this.retrieval = retrieval;
        this.catalog = catalog;
        this.groups = groups;
        this.content = content;
        this.condenser = condenser;
        this.audit = audit;
        this.properties = properties;
        this.knowledge = knowledge;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- drafting

    @Override
    public SkillResponse draft(AccountId admin, SkillDraftRequest request) {
        boolean fromCluster = request.clusterId() != null;
        List<UUID> cardIds = request.cardIds() == null ? List.of() : request.cardIds();
        List<SkillDraftRequest.TurnRef> turnRefs = request.turns() == null ? List.of() : request.turns();
        if (fromCluster == (!cardIds.isEmpty() || !turnRefs.isEmpty())) {
            throw invalid("SOURCES_REQUIRED");
        }
        Reader reader = readerOf(admin);
        List<SourceAnswer> answers = new ArrayList<>();
        List<String> keywords = List.of();
        if (fromCluster) {
            ClusterRow cluster = diagnostics.cluster(request.clusterId())
                    .orElseThrow(() -> new QaServiceException(QaErrorCode.SKILL_CLUSTER_NOT_FOUND, "CLUSTER_NOT_FOUND"));
            keywords = cluster.keywords();
            for (UUID cardId : clusterCards(cluster)) {
                cards.find(cardId).flatMap(card -> fromCard(reader, card)).ifPresent(answers::add);
            }
        } else {
            for (UUID cardId : new LinkedHashSet<>(cardIds)) {
                CardRow card = cards.find(cardId).filter(c -> c.state() == KnowledgeCardState.VERIFIED)
                        .orElseThrow(() -> noSources("CARD_NOT_VERIFIED"));
                answers.add(fromCard(reader, card).orElseThrow(() -> noSources("CARD_SOURCE_UNAVAILABLE")));
            }
            for (SkillDraftRequest.TurnRef ref : turnRefs) answers.add(fromTurn(admin, reader, ref));
        }
        if (answers.isEmpty()) throw noSources("NO_VERIFIED_SOURCES");

        int maxChars = properties.stepMaxChars();
        List<DraftStep> steps = SkillTemplates.steps(answers, SkillRules.MAX_STEPS, maxChars);
        if (steps.isEmpty()) throw noSources("NO_STEPS");
        boolean condensed = false;
        if (request.condense()) {
            SkillCondenser.Outcome outcome = condenser.condense(steps, maxChars);
            steps = outcome.steps();
            condensed = outcome.condensed();
        }
        List<SourceChunk> all = steps.stream().flatMap(s -> s.sources().stream()).toList();
        SkillRules.Scope scope = SkillRules.scope(all);
        if (scope.refusal().isPresent()) throw noSources(scope.refusal().get().name());
        List<String> triggers = SkillTemplates.triggers(answers);
        List<GoldenSeed> golden = SkillTemplates.golden(answers);
        String name = request.name() != null ? request.name() : freeName(SkillRules.slug(fromCluster && !keywords.isEmpty()
                ? String.join(" ", keywords.subList(0, Math.min(3, keywords.size())))
                : triggers.isEmpty() ? "" : triggers.getFirst()));
        if (request.name() != null && skills.nameTaken(name, null)) {
            throw new QaServiceException(QaErrorCode.SKILL_NAME_TAKEN, "SKILL_NAME_TAKEN");
        }
        String description = SkillTemplates.description(triggers, steps.size());
        List<NewStep> newSteps = steps.stream().map(s -> new NewStep(s.instruction(), s.grounded(),
                s.sources().stream().map(c -> new SourceRow(0, c.chunkId(), c.page(), c.unit().name(),
                        c.contentSha256())).toList())).toList();
        List<NewGolden> newGolden = golden.stream().map(g -> new NewGolden(g.question(), g.page(), g.fact())).toList();
        // The model calls of this request (the local embedding model), outside any transaction (llm-rules 5.1).
        List<QuestionEmbedding> triggerVectors = triggers.stream().map(retrieval::embed).toList();
        List<QuestionEmbedding> goldenVectors = newGolden.stream().map(g -> retrieval.embed(g.question())).toList();
        Instant now = clock.instant();
        SkillOrigin origin = fromCluster ? SkillOrigin.CLUSTER : SkillOrigin.MANUAL;
        boolean wasCondensed = condensed;
        UUID skillId;
        try {
            skillId = db.inTransaction(status -> {
                UUID id = skills.insert(new SkillRepository.NewSkill(name, origin, request.clusterId(),
                        scope.collections(), admin.value(), knowledge.requiredVerifiers(), now));
                writeVersion(id, 1, name, description, "DRAFTED", wasCondensed, newSteps, triggers, triggerVectors,
                        newGolden, goldenVectors, now);
                skills.insertEvent(id, 1, null, SkillState.DRAFT, "DRAFTED", now);
                audit.skillDraftedInTransaction(id, origin.name());
                return id;
            });
        } catch (DuplicateKeyException e) {
            throw new QaServiceException(QaErrorCode.SKILL_NAME_TAKEN, "SKILL_NAME_TAKEN");
        }
        log.info("Skill drafted: skillId={} origin={} answers={} steps={} ungrounded={} triggers={} goldenCases={} "
                        + "condensed={}", skillId, origin, answers.size(), newSteps.size(),
                newSteps.stream().filter(s -> !s.grounded()).count(), triggers.size(), newGolden.size(), condensed);
        return get(admin, skillId);
    }

    /** The VERIFIED cards whose question is near the cluster's centre, most similar first. */
    private List<UUID> clusterCards(ClusterRow cluster) {
        return KnowledgeCardRules.matching(cards.nearestVerified(cluster.centroid(), cluster.embeddingModel(),
                        clock.instant(), CLUSTER_CARDS), properties.clusterCardThreshold())
                .stream().map(KnowledgeCardRepository.Candidate::cardId).toList();
    }

    /** A verified card as draft material, only when the admin reads every cited page and its text is unchanged. */
    private Optional<SourceAnswer> fromCard(Reader reader, CardRow card) {
        List<CitationRow> citations = cards.citations(card.id());
        List<Binding> bindings = cards.bindings(card.id());
        Set<SourcePage> pages = new LinkedHashSet<>();
        citations.forEach(c -> pages.add(new SourcePage(c.documentId(), c.page())));
        bindings.forEach(b -> pages.add(b.page()));
        if (pages.isEmpty() || pages.size() > DocumentProvenance.MAX_PAGES) return Optional.empty();
        List<SourceChunk> chunks = provenance.sourceChunks(reader, pages);
        if (KnowledgeCardRules.check(bindings, chunks) != KnowledgeCardRules.BindingState.INTACT) return Optional.empty();
        Map<Integer, SourcePage> numbered = new LinkedHashMap<>();
        for (CitationRow citation : citations) {
            SourcePage page = new SourcePage(citation.documentId(), citation.page());
            if (chunks.stream().noneMatch(c -> c.page().equals(page))) return Optional.empty();
            numbered.put(citation.number(), page);
        }
        return Optional.of(new SourceAnswer(card.question(), card.answer(), numbered, chunks, card.keyFacts()));
    }

    /** One of the admin's own sourced answers (another user's answer stays theirs, ADR-0023). */
    private SourceAnswer fromTurn(AccountId admin, Reader reader, SkillDraftRequest.TurnRef ref) {
        OwnedAnswer turn = turns.findOwnedAnswer(admin.value(), ref.conversationId(), ref.turnId())
                .orElseThrow(() -> new QaServiceException(QaErrorCode.TURN_NOT_FOUND, "TURN_NOT_FOUND"));
        List<TurnRepository.CitationRow> cited = turns.citationsOf(turn.id());
        if (!"ANSWERED".equals(turn.outcome()) || cited.isEmpty() || QaSubjects.REDACTED_ANSWER.equals(turn.answer())) {
            throw noSources("TURN_NOT_ANSWERED");
        }
        Map<Integer, SourcePage> numbered = new LinkedHashMap<>();
        cited.forEach(c -> numbered.put(c.number(), new SourcePage(c.documentId(), c.page())));
        Set<SourcePage> pages = new LinkedHashSet<>(numbered.values());
        if (pages.size() > DocumentProvenance.MAX_PAGES) throw noSources("TOO_MANY_SOURCES");
        List<SourceChunk> chunks = provenance.sourceChunks(reader, pages);
        for (SourcePage page : pages) {
            if (chunks.stream().noneMatch(c -> c.page().equals(page))) throw noSources("TURN_SOURCE_UNAVAILABLE");
        }
        return new SourceAnswer(turn.question(), turn.answer(), numbered, chunks, List.of());
    }

    /** The first free name of {@code base}, {@code base-2}, … (a race is caught by the unique index). */
    private String freeName(String base) {
        for (int n = 1; n <= 50; n++) {
            String candidate = SkillRules.withSuffix(base, n);
            if (!skills.nameTaken(candidate, null)) return candidate;
        }
        throw new QaServiceException(QaErrorCode.SKILL_NAME_TAKEN, "SKILL_NAME_TAKEN");
    }

    /** Writes one version (inside the caller's transaction). */
    private void writeVersion(UUID id, int version, String name, String description, String reason, boolean condensed,
                              List<NewStep> steps, List<String> triggers, List<QuestionEmbedding> triggerVectors,
                              List<NewGolden> golden, List<QuestionEmbedding> goldenVectors, Instant now) {
        skills.insertVersion(id, version, name, description, reason, condensed, now);
        for (int i = 0; i < steps.size(); i++) {
            NewStep step = steps.get(i);
            skills.insertStep(id, version, i + 1, step.instruction(), step.grounded());
            for (SourceRow source : step.sources()) {
                skills.insertSource(id, version, i + 1, source.chunkId(), source.page(), source.unit(),
                        source.contentSha256());
            }
        }
        for (int i = 0; i < triggers.size(); i++) {
            QuestionEmbedding vector = triggerVectors.get(i);
            skills.insertTrigger(id, version, i + 1, triggers.get(i), vector.vector(), vector.model());
        }
        for (int i = 0; i < golden.size(); i++) {
            NewGolden g = golden.get(i);
            QuestionEmbedding vector = goldenVectors.get(i);
            skills.insertGolden(id, version, i + 1, g.question(), g.page(), g.fact(), vector.vector(), vector.model());
        }
    }

    // ---------------------------------------------------------------- reading

    @Override
    public PageResponse<SkillSummary> list(AccountId admin, SkillState state, int page, int size) {
        List<SkillRow> found = skills.list(state, page, size);
        List<UUID> ids = found.stream().map(SkillRow::id).toList();
        Map<UUID, Set<UUID>> sources = skills.sourceDocuments(ids);
        Map<UUID, String> descriptions = skills.descriptions(ids);
        Set<UUID> readable = readableDocuments(readerOf(admin),
                sources.values().stream().flatMap(Set::stream).distinct().toList());
        List<SkillSummary> rows = found.stream().map(skill -> {
            Set<UUID> documents = sources.getOrDefault(skill.id(), Set.of());
            boolean visible = !documents.isEmpty() && readable.containsAll(documents);
            return new SkillSummary(skill.id(), skill.name(), visible ? descriptions.get(skill.id()) : null,
                    skill.state(), skill.staleReason(), skill.version(), skill.origin(),
                    skills.steps(skill.id(), skill.version()).size(), skills.golden(skill.id(), skill.version()).size(),
                    skills.countApprovals(skill.id(), skill.cycle()), skill.requiredApprovers(), skill.approvedAt(),
                    skill.updatedAt(), visible);
        }).toList();
        return PageResponse.of(rows, page, size, skills.count(state));
    }

    @Override
    public List<SkillSuggestion> suggestions(AccountId admin) {
        Optional<UUID> run = diagnostics.latestSucceededRun();
        if (run.isEmpty()) return List.of();
        List<SkillSuggestion> suggestions = new ArrayList<>();
        for (ClusterRow cluster : diagnostics.clusters(run.get())) {
            List<UUID> near = clusterCards(cluster);
            boolean procedural = near.stream().map(cards::find).flatMap(Optional::stream)
                    .anyMatch(card -> SkillRules.procedural(card.question()));
            suggestions.add(new SkillSuggestion(cluster.id(), run.get(), cluster.keywords(), cluster.turns(),
                    cluster.askers(), cluster.trend(), near.size(), procedural));
        }
        suggestions.sort(Comparator.comparing((SkillSuggestion s) -> !s.procedural())
                .thenComparing(s -> -s.verifiedCards()));
        return List.copyOf(suggestions);
    }

    @Override
    public SkillResponse get(AccountId admin, UUID skillId) {
        SkillRow skill = skills.find(skillId).orElseThrow(SkillServiceImpl::notFound);
        Loaded loaded = content.load(skill).orElseThrow(SkillServiceImpl::notFound);
        List<UUID> documents = SkillContent.documents(loaded.sources());
        Map<UUID, DocumentAccess> access = documents.isEmpty() ? Map.of() : catalog.access(readerOf(admin), documents);
        // ADR-0028: a skill's text is derived from document content; an admin without a grant on every source sees none.
        boolean visible = !documents.isEmpty() && documents.stream().allMatch(d -> access.containsKey(d)
                && access.get(d).accessible());
        List<SkillResponse.Step> steps = new ArrayList<>();
        for (StepRow step : loaded.steps()) {
            List<SkillResponse.Source> sources = new ArrayList<>();
            for (SourcePage page : SkillContent.stepPages(loaded.sources(), step.position())) {
                DocumentAccess a = access.get(page.documentId());
                boolean accessible = a != null && a.accessible();
                String unit = loaded.sources().stream().filter(s -> s.page().equals(page)).map(SourceRow::unit)
                        .findFirst().orElse("PAGE");
                sources.add(new SkillResponse.Source(page.documentId(), accessible ? a.fileName() : null,
                        page.pageNumber(), CitationUnit.valueOf(unit), accessible));
            }
            steps.add(new SkillResponse.Step(step.position(), visible ? step.instruction() : null, step.grounded(),
                    List.copyOf(sources)));
        }
        List<SkillResponse.GoldenCase> golden = visible ? loaded.golden().stream().map(g -> new SkillResponse.GoldenCase(
                g.question(), g.page().documentId(), g.page().pageNumber(), g.fact())).toList() : List.of();
        List<SkillResponse.Event> events = skills.events(skillId).stream().map(r -> new SkillResponse.Event((Integer) r[0],
                r[1] == null ? null : SkillState.valueOf((String) r[1]), SkillState.valueOf((String) r[2]), (String) r[3],
                (Instant) r[4])).toList();
        List<SkillResponse.VersionInfo> versions = skills.versions(skillId).stream()
                .map(v -> new SkillResponse.VersionInfo(v.version(), v.reason(), v.condensed(), v.createdAt())).toList();
        return new SkillResponse(skill.id(), skill.name(), visible ? loaded.version().description() : null, skill.state(),
                skill.staleReason(), skill.version(), skill.origin(), skill.originClusterId(),
                loaded.version().condensed(), List.copyOf(steps), visible ? loaded.triggers() : List.of(), golden,
                skills.countApprovals(skillId, skill.cycle()), skill.requiredApprovers(),
                skills.approvedBy(skillId, skill.cycle(), admin.value()), admin.value().equals(skill.createdBy()),
                events, versions, skill.approvedAt(), skill.createdAt(), skill.updatedAt(), visible);
    }

    @Override
    public SkillVersionDiff diff(AccountId admin, UUID skillId, int from, int to) {
        SkillRow skill = skills.find(skillId).orElseThrow(SkillServiceImpl::notFound);
        if (from < 1 || from >= to || to > skill.version()) throw invalid("VERSION_RANGE");
        SkillDiff.Snapshot before = snapshot(skillId, from);
        SkillDiff.Snapshot after = snapshot(skillId, to);
        Set<UUID> documents = new LinkedHashSet<>(SkillContent.documents(skills.sources(skillId, from)));
        documents.addAll(SkillContent.documents(skills.sources(skillId, to)));
        boolean visible = !documents.isEmpty()
                && readableDocuments(readerOf(admin), List.copyOf(documents)).containsAll(documents);
        return SkillDiff.diff(before, after, visible);
    }

    private SkillDiff.Snapshot snapshot(UUID skillId, int version) {
        VersionRow row = skills.version(skillId, version).orElseThrow(SkillServiceImpl::notFound);
        return new SkillDiff.Snapshot(version, row.name(), row.description(),
                skills.steps(skillId, version).stream().map(StepRow::instruction).toList(),
                skills.triggers(skillId, version),
                skills.golden(skillId, version).stream().map(g -> g.question() + " → " + g.page().documentId() + " s. "
                        + g.page().pageNumber() + (g.fact() == null ? "" : " (" + g.fact() + ")")).toList());
    }

    // ---------------------------------------------------------------- editing

    @Override
    public SkillResponse update(AccountId admin, UUID skillId, SkillUpdateRequest request) {
        SkillRow skill = skills.find(skillId).orElseThrow(SkillServiceImpl::notFound);
        if (!SkillRules.canEdit(skill.state())) {
            throw new QaServiceException(QaErrorCode.SKILL_STATE_CONFLICT, "STATE_" + skill.state().name());
        }
        Loaded loaded = content.load(skill).orElseThrow(SkillServiceImpl::notFound);
        int current = loaded.steps().size();
        Set<Integer> used = new HashSet<>();
        List<SourceRow> newSources = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            int from = request.steps().get(i).from();
            if (from < 1 || from > current || !used.add(from)) throw invalid("STEP_FROM");
            int position = i + 1;
            loaded.sources().stream().filter(s -> s.position() == from)
                    .forEach(s -> newSources.add(new SourceRow(position, s.chunkId(), s.page(), s.unit(), s.contentSha256())));
        }
        Set<SourcePage> bound = new LinkedHashSet<>();
        newSources.forEach(s -> bound.add(s.page()));
        for (SkillUpdateRequest.GoldenCase g : request.goldenCases()) {
            if (!bound.contains(new SourcePage(g.documentId(), g.page()))) throw invalid("GOLDEN_PAGE");
        }
        if (!request.name().equals(skill.name()) && skills.nameTaken(request.name(), skillId)) {
            throw new QaServiceException(QaErrorCode.SKILL_NAME_TAKEN, "SKILL_NAME_TAKEN");
        }
        // The editor must read every source now, unchanged: the steps are re-checked against that text.
        View view = content.view(readerOf(admin), newSources);
        if (!view.intact()) throw new QaServiceException(QaErrorCode.SKILL_SOURCE_CHANGED, "SKILL_SOURCE_CHANGED");
        List<SourceChunk> visible = view.byPage().values().stream().flatMap(List::stream).toList();
        SkillRules.Scope scope = SkillRules.scope(visible);
        if (scope.refusal().isPresent()) throw noSources(scope.refusal().get().name());
        List<NewStep> steps = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            int position = i + 1;
            String instruction = request.steps().get(i).instruction().strip();
            List<SourceRow> stepSources = newSources.stream().filter(s -> s.position() == position).toList();
            boolean grounded = SkillTemplates.grounded(instruction, SkillContent.stepChunks(newSources, position, view));
            steps.add(new NewStep(instruction, grounded, stepSources));
        }
        List<String> triggers = request.triggers().stream().map(String::strip).distinct().toList();
        List<NewGolden> golden = request.goldenCases().stream().map(g -> new NewGolden(g.question().strip(),
                new SourcePage(g.documentId(), g.page()), g.fact() == null ? null : g.fact().strip())).toList();
        List<QuestionEmbedding> triggerVectors = triggers.stream().map(retrieval::embed).toList();
        List<QuestionEmbedding> goldenVectors = golden.stream().map(g -> retrieval.embed(g.question())).toList();
        Instant now = clock.instant();
        int version = skill.version() + 1;
        try {
            db.inTransaction(status -> {
                SkillRow locked = skills.lock(skillId).orElseThrow(SkillServiceImpl::notFound);
                if (!SkillRules.canEdit(locked.state()) || locked.version() != skill.version()) {
                    throw new QaServiceException(QaErrorCode.SKILL_STATE_CONFLICT, "STATE_CHANGED");
                }
                writeVersion(skillId, version, request.name(), request.description().strip(), "EDITED", false, steps,
                        triggers, triggerVectors, golden, goldenVectors, now);
                if (!skills.newVersion(skillId, locked.state(), version, request.name(), scope.collections(), now)) {
                    throw new QaServiceException(QaErrorCode.SKILL_STATE_CONFLICT, "STATE_CHANGED");
                }
                skills.insertEvent(skillId, version, locked.state(), SkillState.DRAFT, "EDITED", now);
                audit.skillEditedInTransaction(skillId);
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw new QaServiceException(QaErrorCode.SKILL_NAME_TAKEN, "SKILL_NAME_TAKEN");
        }
        log.info("Skill edited: skillId={} version={} steps={} ungrounded={} triggers={} goldenCases={}", skillId, version,
                steps.size(), steps.stream().filter(s -> !s.grounded()).count(), triggers.size(), golden.size());
        return get(admin, skillId);
    }

    // ---------------------------------------------------------------- approval and archive

    @Override
    public SkillResponse approve(AccountId admin, UUID skillId) {
        SkillRow skill = skills.find(skillId).orElseThrow(SkillServiceImpl::notFound);
        if (!SkillRules.canApprove(skill.state())) {
            throw new QaServiceException(QaErrorCode.SKILL_STATE_CONFLICT, "STATE_" + skill.state().name());
        }
        Loaded loaded = content.load(skill).orElseThrow(SkillServiceImpl::notFound);
        // The approver must read every source now, unchanged; the steps are checked against that text again.
        View view = content.view(readerOf(admin), loaded.sources());
        if (!view.intact()) throw new QaServiceException(QaErrorCode.SKILL_SOURCE_CHANGED, "SKILL_SOURCE_CHANGED");
        List<Boolean> grounded = loaded.steps().stream().map(step -> SkillTemplates.grounded(step.instruction(),
                SkillContent.stepChunks(loaded.sources(), step.position(), view))).toList();
        List<SkillRules.GoldenCheck> golden = loaded.golden().stream()
                .map(g -> new SkillRules.GoldenCheck(g.page(), g.fact())).toList();
        Set<SourcePage> bound = new LinkedHashSet<>();
        loaded.sources().forEach(s -> bound.add(s.page()));
        List<String> problems = SkillRules.approvalProblems(loaded.steps().size(), grounded, loaded.triggers().size(),
                golden, bound, page -> pageText(view, page));
        if (problems.contains("STEP_UNGROUNDED")) {
            throw new QaServiceException(QaErrorCode.SKILL_STEP_UNGROUNDED, "SKILL_STEP_UNGROUNDED");
        }
        if (!problems.isEmpty()) throw invalid(problems.getFirst());
        Instant now = clock.instant();
        db.inTransaction(status -> {
            SkillRow locked = skills.lock(skillId).orElseThrow(SkillServiceImpl::notFound);
            if (!SkillRules.canApprove(locked.state()) || locked.version() != skill.version()) {
                throw new QaServiceException(QaErrorCode.SKILL_STATE_CONFLICT, "STATE_CHANGED");
            }
            if (!skills.insertApproval(skillId, locked.cycle(), admin.value(), now)) {
                throw new QaServiceException(QaErrorCode.SKILL_ALREADY_APPROVED_BY_YOU, "ALREADY_APPROVED_BY_YOU");
            }
            int approvals = skills.countApprovals(skillId, locked.cycle());
            if (SkillRules.afterApproval(approvals, locked.requiredApprovers()) == SkillState.APPROVED) {
                if (!skills.markApproved(skillId, locked.version(), now)) {
                    throw new QaServiceException(QaErrorCode.SKILL_STATE_CONFLICT, "STATE_CHANGED");
                }
                skills.insertEvent(skillId, locked.version(), SkillState.DRAFT, SkillState.APPROVED, "APPROVED", now);
                audit.skillApprovedInTransaction(skillId, "APPROVED");
            } else {
                skills.insertEvent(skillId, locked.version(), SkillState.DRAFT, SkillState.DRAFT, "APPROVAL_ADDED", now);
                audit.skillApprovedInTransaction(skillId, "APPROVAL_ADDED");
            }
            return null;
        });
        log.info("Skill approval recorded: skillId={} version={}", skillId, skill.version());
        return get(admin, skillId);
    }

    private static String pageText(View view, SourcePage page) {
        List<SourceChunk> chunks = view.byPage().get(page);
        if (chunks == null) return null;
        return String.join("\n", chunks.stream().map(c -> c.content() == null ? "" : c.content()).toList());
    }

    @Override
    public SkillResponse archive(AccountId admin, UUID skillId) {
        Instant now = clock.instant();
        db.inTransaction(status -> {
            SkillState before = skills.archive(skillId, now).orElseGet(() -> {
                skills.find(skillId).orElseThrow(SkillServiceImpl::notFound);
                throw new QaServiceException(QaErrorCode.SKILL_STATE_CONFLICT, "STATE_ARCHIVED");
            });
            int version = skills.find(skillId).map(SkillRow::version).orElse(1);
            skills.insertEvent(skillId, version, before, SkillState.ARCHIVED, "ARCHIVED", now);
            audit.skillArchivedInTransaction(skillId);
            return null;
        });
        log.info("Skill archived: skillId={}", skillId);
        return get(admin, skillId);
    }

    // ---------------------------------------------------------------- export

    @Override
    public Export export(AccountId admin, UUID skillId) {
        SkillRow skill = skills.find(skillId).orElseThrow(SkillServiceImpl::notFound);
        if (!SkillRules.servable(skill.state())) {
            throw new QaServiceException(QaErrorCode.SKILL_NOT_APPROVED, "SKILL_NOT_APPROVED");
        }
        Loaded loaded = content.load(skill).orElseThrow(SkillServiceImpl::notFound);
        View view = content.view(readerOf(admin), loaded.sources());
        if (!view.intact()) throw new QaServiceException(QaErrorCode.SKILL_SOURCE_CHANGED, "SKILL_SOURCE_CHANGED");
        String markdown = SkillContent.markdown(loaded, view);
        List<String> problems = SkillMarkdown.validate(markdown);
        if (!problems.isEmpty()) throw new IllegalStateException("SKILL.md invalid: " + problems);
        byte[] zip = zip(skill.name() + "/SKILL.md", markdown);
        audit.skillExported(skillId, SkillContent.documents(loaded.sources()));
        log.info("Skill exported: skillId={} version={} bytes={}", skillId, skill.version(), zip.length);
        return new Export(skill.name() + ".zip", zip);
    }

    static byte[] zip(String entry, String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("zip failed", e);
        }
        return bytes.toByteArray();
    }

    // ---------------------------------------------------------------- helpers

    /** The documents among {@code ids} this reader can read now (one statement). */
    private Set<UUID> readableDocuments(Reader reader, List<UUID> ids) {
        if (ids.isEmpty()) return Set.of();
        Set<UUID> readable = new HashSet<>();
        catalog.access(reader, ids).forEach((id, a) -> {
            if (a.accessible()) readable.add(id);
        });
        return readable;
    }

    private Reader readerOf(AccountId account) {
        return new Reader(account.value(), groups.current());
    }

    private static QaServiceException notFound() {
        return new QaServiceException(QaErrorCode.SKILL_NOT_FOUND, "SKILL_NOT_FOUND");
    }

    private static QaServiceException noSources(String reason) {
        return new QaServiceException(QaErrorCode.SKILL_NO_SOURCES, reason);
    }

    private static QaServiceException invalid(String reason) {
        return new QaServiceException(QaErrorCode.SKILL_INVALID, reason);
    }
}
