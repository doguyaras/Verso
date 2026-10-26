package com.verso.qa.service;

import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.api.dto.ProcedureMatch;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.repository.SkillRepository.GoldenRow;
import com.verso.qa.repository.SkillRepository.SkillRow;
import com.verso.qa.repository.SkillRepository.SourceRow;
import com.verso.qa.repository.SkillRepository.StepRow;
import com.verso.qa.repository.SkillRepository.VersionRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * A skill's current version and a reader's view of it (ADR-0053): the bound pages read again <b>with that reader's
 * principals</b> (ADR-0028, inside the document module's query). The view is intact only when every bound (page, hash)
 * is still there for this reader: a page they cannot read is simply absent, so the binding breaks, exactly as for the
 * verified answer cards (ADR-0049). Every way a skill leaves Verso (the panel's procedure box, SKILL.md, MCP) goes
 * through an intact view; nothing else of a skill is served.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillContent {

    /** The current version of a skill with everything under it. */
    public record Loaded(SkillRow skill, VersionRow version, List<StepRow> steps, List<SourceRow> sources,
                         List<String> triggers, List<GoldenRow> golden) {

        @Override
        public String toString() {
            return "Loaded[skill=" + skill.id() + ", version=" + skill.version() + "]";
        }
    }

    /** A reader's view of the bound pages: intact or not, and the chunks this reader reads per page. */
    public record View(boolean intact, Map<SourcePage, List<SourceChunk>> byPage) {

        @Override
        public String toString() {
            return "View[intact=" + intact + ", pages=" + byPage.size() + "]";
        }
    }

    private final SkillRepository skills;
    private final DocumentProvenance provenance;

    public SkillContent(SkillRepository skills, DocumentProvenance provenance) {
        this.skills = skills;
        this.provenance = provenance;
    }

    public Optional<Loaded> load(SkillRow skill) {
        return skills.version(skill.id(), skill.version()).map(version -> new Loaded(skill, version,
                skills.steps(skill.id(), skill.version()), skills.sources(skill.id(), skill.version()),
                skills.triggers(skill.id(), skill.version()), skills.golden(skill.id(), skill.version())));
    }

    /** The bound pages read with {@code reader}'s principals; intact when every bound hash is there. */
    public View view(Reader reader, List<SourceRow> sources) {
        List<Binding> bindings = bindings(sources);
        Set<SourcePage> pages = SkillRules.pages(bindings);
        if (pages.isEmpty() || pages.size() > DocumentProvenance.MAX_PAGES) return new View(false, Map.of());
        List<SourceChunk> visible = provenance.sourceChunks(reader, pages);
        Map<SourcePage, List<SourceChunk>> byPage = visible.stream()
                .collect(Collectors.groupingBy(SourceChunk::page, LinkedHashMap::new, Collectors.toList()));
        return new View(SkillRules.intact(bindings, visible), byPage);
    }

    public static List<Binding> bindings(List<SourceRow> sources) {
        return sources.stream().map(s -> new Binding(s.chunkId(), s.page(), s.contentSha256())).toList();
    }

    /** The bound pages of one step, in order. */
    public static List<SourcePage> stepPages(List<SourceRow> sources, int position) {
        Set<SourcePage> pages = new LinkedHashSet<>();
        sources.stream().filter(s -> s.position() == position).forEach(s -> pages.add(s.page()));
        return List.copyOf(pages);
    }

    /** The chunks of one step as this view reads them (the groundedness check's sources). */
    public static List<SourceChunk> stepChunks(List<SourceRow> sources, int position, View view) {
        List<SourceChunk> chunks = new ArrayList<>();
        for (SourcePage page : stepPages(sources, position)) chunks.addAll(view.byPage().getOrDefault(page, List.of()));
        return chunks;
    }

    /**
     * The verso_fetch id of a bound page: a chunk this reader reads that carries bound text (by page and hash, so a
     * re-embedded page still links), else null.
     */
    public static UUID passageOf(List<SourceRow> sources, SourcePage page, View view) {
        Set<String> hashes = sources.stream().filter(s -> s.page().equals(page)).map(SourceRow::contentSha256)
                .collect(Collectors.toSet());
        return view.byPage().getOrDefault(page, List.of()).stream().filter(c -> hashes.contains(c.contentSha256()))
                .map(SourceChunk::chunkId).findFirst().orElse(null);
    }

    /** The procedure box of the answer (ADR-0053); only for an intact view. */
    public static ProcedureMatch procedure(Loaded loaded, View view) {
        if (!view.intact()) throw new IllegalStateException("view is not intact");
        List<ProcedureMatch.Step> steps = new ArrayList<>();
        for (StepRow step : loaded.steps()) {
            List<ProcedureMatch.Source> sources = new ArrayList<>();
            for (SourcePage page : stepPages(loaded.sources(), step.position())) {
                SourceChunk first = view.byPage().get(page).getFirst();
                sources.add(new ProcedureMatch.Source(page.documentId(), first.fileName(), page.pageNumber(),
                        unit(first.unit()), passageOf(loaded.sources(), page, view)));
            }
            steps.add(new ProcedureMatch.Step(step.position(), step.instruction(), List.copyOf(sources)));
        }
        return new ProcedureMatch(loaded.skill().id(), loaded.version().name(), loaded.version().description(),
                List.copyOf(steps));
    }

    /** SKILL.md (ADR-0053); only for an intact view. References name a page by its document id, never its file name. */
    public static String markdown(Loaded loaded, View view) {
        if (!view.intact()) throw new IllegalStateException("view is not intact");
        List<SkillMarkdown.Step> steps = new ArrayList<>();
        for (StepRow step : loaded.steps()) {
            List<SkillMarkdown.Reference> references = new ArrayList<>();
            for (SourcePage page : stepPages(loaded.sources(), step.position())) {
                UUID passage = passageOf(loaded.sources(), page, view);
                if (passage == null) continue;
                SourceChunk first = view.byPage().get(page).getFirst();
                references.add(new SkillMarkdown.Reference(passage, "Belge " + page.documentId().toString().substring(0, 8)
                        + (first.unit() == SourceUnit.SECTION ? ", bölüm " : ", s. ") + page.pageNumber()));
            }
            steps.add(new SkillMarkdown.Step(step.instruction(), references));
        }
        return SkillMarkdown.render(loaded.skill().id(), loaded.version().name(), loaded.version().description(),
                loaded.skill().version(), loaded.skill().approvedAt(), loaded.triggers(), steps);
    }

    /** The source documents of a version (the audit row of a read). */
    public static List<UUID> documents(List<SourceRow> sources) {
        Set<UUID> documents = new LinkedHashSet<>();
        sources.forEach(s -> documents.add(s.page().documentId()));
        return List.copyOf(documents);
    }

    public static CitationUnit unit(SourceUnit unit) {
        return unit == SourceUnit.SECTION ? CitationUnit.SECTION : CitationUnit.PAGE;
    }
}
