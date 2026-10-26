package com.verso.qa.api;

import com.verso.qa.api.dto.SkillDocument;
import com.verso.qa.api.dto.SkillListing;
import java.util.List;
import java.util.Optional;

/**
 * In-process contract of the approved skills (ADR-0053) for the MCP server (ADR-0031): what the caller may read, as the
 * caller. The reader is the validated token's sub and groups, taken from the token by the calling module, never from a
 * request. A skill is visible only when the caller reads every document it is bound to (ADR-0028), filtered inside the
 * document module's queries. No model call. An unknown name and a skill the caller may not read look the same (empty).
 */
public interface QaSkills {

    /** At most this many skills per listing. */
    int MAX_LISTED = 100;

    /** The APPROVED skills this caller reads (every source document readable), by name; at most {@link #MAX_LISTED}. */
    List<SkillListing> approvedSkills(String accountId, List<String> groups);

    /**
     * One APPROVED skill by name as SKILL.md, only when the caller reads every bound chunk now and every bound hash is
     * still there.
     */
    Optional<SkillDocument> approvedSkill(String accountId, List<String> groups, String name);
}
