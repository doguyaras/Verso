package com.verso.qa.service.impl;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.dto.Reader;
import com.verso.qa.api.QaSkills;
import com.verso.qa.api.dto.SkillDocument;
import com.verso.qa.api.dto.SkillListing;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.repository.SkillRepository.SkillRow;
import com.verso.qa.service.SkillContent;
import com.verso.qa.service.SkillRules;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * The approved skills for the MCP server (ADR-0053, {@link QaSkills}), as the caller: the listing keeps a skill only
 * when the caller reads every one of its source documents (one catalog statement for the page); one skill's SKILL.md
 * is built only from an intact view, every bound chunk read again with the caller's principals and every hash still
 * there (ADR-0028). Names that are unknown, archived, not approved or not readable all give the same empty result.
 * The calling module audits (ids only); nothing here is logged.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaSkillsServiceImpl implements QaSkills {

    private final SkillRepository skills;
    private final SkillContent content;
    private final DocumentCatalog catalog;

    public QaSkillsServiceImpl(SkillRepository skills, SkillContent content, DocumentCatalog catalog) {
        this.skills = skills;
        this.content = content;
        this.catalog = catalog;
    }

    @Override
    public List<SkillListing> approvedSkills(String accountId, List<String> groups) {
        Reader reader = new Reader(accountId, groups);
        List<SkillRow> approved = skills.approved(MAX_LISTED);
        if (approved.isEmpty()) return List.of();
        List<UUID> ids = approved.stream().map(SkillRow::id).toList();
        Map<UUID, Set<UUID>> sources = skills.sourceDocuments(ids);
        Map<UUID, String> descriptions = skills.descriptions(ids);
        Set<UUID> readable = new HashSet<>();
        List<UUID> documents = sources.values().stream().flatMap(Set::stream).distinct().toList();
        if (!documents.isEmpty()) {
            catalog.access(reader, documents).forEach((id, a) -> {
                if (a.accessible()) readable.add(id);
            });
        }
        return approved.stream().filter(skill -> {
            Set<UUID> needed = sources.getOrDefault(skill.id(), Set.of());
            return !needed.isEmpty() && readable.containsAll(needed);
        }).map(skill -> new SkillListing(skill.id(), skill.name(), descriptions.get(skill.id()))).toList();
    }

    @Override
    public Optional<SkillDocument> approvedSkill(String accountId, List<String> groups, String name) {
        if (!SkillRules.validName(name)) return Optional.empty();
        Reader reader = new Reader(accountId, groups);
        Optional<SkillRow> skill = skills.findLive(name).filter(s -> SkillRules.servable(s.state()));
        if (skill.isEmpty()) return Optional.empty();
        Optional<SkillContent.Loaded> loaded = content.load(skill.get());
        if (loaded.isEmpty()) return Optional.empty();
        SkillContent.View view = content.view(reader, loaded.get().sources());
        if (!view.intact()) return Optional.empty();
        return Optional.of(new SkillDocument(skill.get().id(), skill.get().name(), skill.get().version(),
                SkillContent.markdown(loaded.get(), view), SkillContent.documents(loaded.get().sources())));
    }
}
