package com.verso.work.service;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillSummary;
import com.verso.work.api.enums.SkillOrigin;
import com.verso.work.exception.WorkErrorCode;
import com.verso.work.exception.WorkServiceException;
import com.verso.work.repository.SkillCatalogRepository;
import com.verso.work.repository.SkillCatalogRepository.CustomRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What users can run (ADR-0057): the built-in skills the installation did not switch off, and the customer-authored
 * skills a verso-admin approved and did not switch off. Read from the database on every call (no JVM cache: an admin's
 * switch on one instance holds on every instance at once, AGENTS.md 10).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillCatalog {

    /** A runnable skill with where it comes from. */
    public record Entry(SkillDefinition definition, SkillOrigin origin, UUID customSkillId) {
    }

    private final BuiltInSkills builtIn;
    private final SkillCatalogRepository repository;
    private final JsonMapper reader = BuiltInSkills.strictReader();

    public SkillCatalog(BuiltInSkills builtIn, SkillCatalogRepository repository) {
        this.builtIn = builtIn;
        this.repository = repository;
    }

    /** Enabled skills, built-in first, by name. */
    public List<Entry> enabled() {
        Map<String, Boolean> switches = repository.switches();
        List<Entry> entries = new ArrayList<>();
        for (SkillDefinition d : builtIn.all()) {
            if (switches.getOrDefault(d.id(), true)) entries.add(new Entry(d, SkillOrigin.BUILT_IN, null));
        }
        for (CustomRow row : repository.live()) {
            if ("APPROVED".equals(row.state()) && switches.getOrDefault(row.skillId(), true)) {
                entries.add(new Entry(definition(row), SkillOrigin.CUSTOM, row.id()));
            }
        }
        entries.sort(Comparator.comparing((Entry e) -> e.origin()).thenComparing(e -> e.definition().name()));
        return entries;
    }

    /** One enabled skill; 404 SKILL_NOT_FOUND when it does not exist, SKILL_DISABLED when it is off or not approved. */
    public Entry require(String skillId) {
        if (skillId == null || !com.verso.work.domain.SkillRules.ID.matcher(skillId).matches()) {
            throw new WorkServiceException(WorkErrorCode.SKILL_NOT_FOUND, "ID_INVALID");
        }
        for (Entry e : enabled()) {
            if (e.definition().id().equals(skillId)) return e;
        }
        boolean exists = builtIn.contains(skillId) || repository.live().stream().anyMatch(r -> r.skillId().equals(skillId));
        throw new WorkServiceException(exists ? WorkErrorCode.SKILL_DISABLED : WorkErrorCode.SKILL_NOT_FOUND,
                exists ? "DISABLED" : "UNKNOWN");
    }

    public SkillDefinition definition(CustomRow row) {
        return reader.readValue(row.definitionJson(), SkillDefinition.class);
    }

    public static SkillSummary summary(SkillDefinition d, SkillOrigin origin) {
        return new SkillSummary(d.id(), d.version(), d.name(), d.description(), d.category(), d.slash(), origin,
                d.grounding() != null, Boolean.TRUE.equals(d.compliance()), d.steps().size(), d.disclaimer());
    }
}
