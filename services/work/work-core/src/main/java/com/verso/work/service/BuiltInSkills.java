package com.verso.work.service;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.domain.SkillRules;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The skills shipped with Verso (ADR-0057): {@code skills/*.json} of the repository, packaged into work-core. Loaded
 * once and validated with the same rules as an admin's definition; an invalid file stops the start-up (a release
 * never ships a skill that would fail its own checks). Unknown fields are refused, so a typo is not silently ignored.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class BuiltInSkills {

    /** Where the jar carries the repository's skills (work-core pom). */
    public static final String LOCATION = "classpath*:skills/*.json";

    private final Map<String, SkillDefinition> skills;

    /** The shipped skills, validated; an invalid one stops the start-up. */
    public BuiltInSkills() {
        this(load(LOCATION).skills);
    }

    private BuiltInSkills(Map<String, SkillDefinition> skills) {
        this.skills = skills;
    }

    /** A strict reader for definitions: unknown properties fail. */
    public static JsonMapper strictReader() {
        return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public static BuiltInSkills load(String pattern) {
        JsonMapper reader = strictReader();
        List<SkillDefinition> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(pattern);
            for (Resource file : files) {
                String name = file.getFilename();
                try (InputStream in = file.getInputStream()) {
                    SkillDefinition d = reader.readValue(in, SkillDefinition.class);
                    List<String> problems = SkillRules.validate(d, true);
                    problems.forEach(p -> errors.add(name + " " + p));
                    if (d.id() != null && name != null && !name.equals(d.id() + ".json")) {
                        errors.add(name + " id: must equal the file name");
                    }
                    loaded.add(d);
                } catch (RuntimeException e) {
                    errors.add(name + " unreadable: " + e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("built-in skills cannot be listed", e);
        }
        errors.addAll(SkillRules.uniqueness(loaded));
        if (!errors.isEmpty()) throw new IllegalStateException("invalid built-in skills: " + errors);
        Map<String, SkillDefinition> byId = new LinkedHashMap<>();
        loaded.stream().sorted(Comparator.comparing(SkillDefinition::id)).forEach(d -> byId.put(d.id(), d));
        return new BuiltInSkills(Map.copyOf(byId));
    }

    public List<SkillDefinition> all() {
        return skills.values().stream().sorted(Comparator.comparing(SkillDefinition::id)).toList();
    }

    public Optional<SkillDefinition> find(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public boolean contains(String id) {
        return skills.containsKey(id);
    }

    public boolean slashTaken(String slash) {
        return skills.values().stream().anyMatch(d -> d.slash().equals(slash));
    }
}
